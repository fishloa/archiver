# MCP per-user OAuth — design

Date: 2026-07-28
Status: approved, pending implementation
Branch: TBD (new branch off `main`)

## Goal

Replace the shared static `MCP_TOKEN` bearer secret on `/api/mcp/**` with real
per-user OAuth, so MCP access is auditable and revocable per person instead of
being a single password everyone with the string shares. Reuse the identity
mechanism already proven on this stack (Google/Apple via `oauth2-proxy`) rather
than standing up a second identity source.

## Current state (verified 2026-07-28)

- `/api/mcp/**` is gated by a custom `McpTokenFilter` checking
  `Authorization: Bearer <MCP_TOKEN>` (constant-time compare), granting
  `ROLE_MCP`. One shared secret, no per-user identity, no revocation short of
  rotating the secret for everyone.
- No OAuth2 infrastructure exists in the backend today — no resource server, no
  authorization server. `build.gradle.kts` has no
  `spring-security-oauth2-*` dependency of any kind.
- The site's only proven login mechanism is `oauth2-proxy` (Google + Apple),
  terminating in a session cookie; nginx reads that cookie via `auth_request`
  and sets `X-Auth-Email`, which `ProxyAuthFilter` turns into a Spring Security
  `Authentication` — but only when the request's TCP peer is trusted (part of
  the auth lockdown completed earlier this session).
- Backend: Spring Boot 4.0.2, Spring AI BOM 2.0.0-M2 (a milestone, not GA).
  Spring AI 2.0.0 went GA 2026-06-12 and requires Spring Boot 4.1 + Spring
  Framework 7 — the backend is currently on a version combination Spring AI's
  own GA release doesn't target.

## Decisions made during brainstorming

1. **Library: Spring Authorization Server**, via the community
   `org.springaicommunity:mcp-authorization-server-spring-boot` and
   `mcp-server-security-spring-boot` (currently 0.1.13) rather than hand-rolling
   the MCP-spec-specific endpoints. Chosen deliberately over the lighter
   hand-rolled option after the risk was stated plainly: this add-on is
   pre-1.0 and its own README says "not officially endorsed by Spring AI or the
   MCP project." Spring's own `spring-security-oauth2-authorization-server`
   (the module `mcp-authorization-server` sits on top of) is stable and
   officially supported; only the MCP-specific sugar layer (Dynamic Client
   Registration wiring, `/.well-known/oauth-protected-resource`) carries this
   risk.
2. **Version bump: Spring Boot 4.1.latest, Spring AI BOM 2.0.0 GA.** This is
   the officially tested pairing per Spring AI's own release, not a guess.
3. **Login reuse, not a second login.** The Authorization Server's
   `/oauth2/authorize` step must recognise an existing valid oauth2-proxy
   session (the same one the rest of the site trusts) and skip straight to
   issuing the authorization code — no second Google/Apple prompt, no new
   provider app registrations, no new redirect URIs to register with Google or
   Apple.

## Architecture

One Spring context, two roles:

- **Authorization Server role** (new): exposes the OAuth endpoints Claude.ai's
  MCP client needs — `/.well-known/oauth-protected-resource`,
  `/.well-known/oauth-authorization-server`, a Dynamic Client Registration
  endpoint (RFC 7591), `/oauth2/authorize`, `/oauth2/token`.
- **Resource Server role** (replaces `McpTokenFilter`): `/api/mcp/**` validates
  a JWT issued by the Authorization Server role above — same issuer, so this is
  self-issued and self-validated. No external IdP token ever reaches this
  endpoint directly; Google/Apple are used only to authenticate the human at
  the authorize step.

Client library additions to `build.gradle.kts`:

```
implementation("org.springaicommunity:mcp-authorization-server-spring-boot:0.1.13")
implementation("org.springaicommunity:mcp-server-security-spring-boot:0.1.13")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
```

(Exact final versions confirmed at implementation time — 0.1.x is documented as
targeting Spring AI's 2.0.x branch, which is what this spec bumps to.)

## Login flow — the session-reuse filter

A new filter, structurally the same shape as the existing `ProxyAuthFilter`,
sits ahead of the Authorization Server's own security filter chain for
`/oauth2/authorize` only:

- If `X-Auth-Email` is present (nginx already validated the oauth2-proxy
  session and the request's peer is trusted, exactly as `ProxyAuthFilter`
  already requires) and the email is in `app_user_email`, populate a Spring
  Security `Authentication` for that user. The authorize endpoint then issues
  the authorization code immediately — no visible login step at all if the
  browser already has a valid site session.
- If absent, or the email isn't allowlisted, redirect to the existing
  `/signin` page (`?rd=` back to the in-progress authorize request), same as
  every other unauthenticated browser route on this site. No new sign-in UI,
  no new provider registrations.

This is the one piece with no documented library support — Spring AS's
standard extension point here is a custom `AuthenticationProvider`
(confirmed via the library's own docs, which explicitly say there is no
built-in cookie/session pre-authentication hook). Implementation is new code,
not library configuration.

## Client registration

Claude.ai self-registers via DCR when the connector is added in Claude.ai's
settings — no manual client_id provisioning. Per the library's documented
limitation, DCR does not scope *which* client can register or restrict its
resource access; every registered client is equally capable. This is accepted:
the real access-control boundary is the login step above (must resolve to an
allowlisted email), not the OAuth client's identity. This archive has exactly
one realistic MCP client (Claude.ai) and no multi-tenant client population to
scope against.

## Persistence

New Flyway migration adds Spring Authorization Server's standard schema —
`oauth2_registered_client`, `oauth2_authorization`,
`oauth2_authorization_consent` — to the existing Postgres database. No new
datastore. `JdbcRegisteredClientRepository` / `JdbcOAuth2AuthorizationService`
/ `JdbcOAuth2AuthorizationConsentService` (Spring's own, documented, standard
JDBC-backed implementations) back these tables.

## Signing key

One RSA keypair signs issued JWTs. Stored as a Portainer stack secret
(base64-encoded), loaded into a `JWKSource` bean at startup — the same pattern
already in production for `APPLE_KEY_P8_B64`. No rotation for this version;
a single key is sufficient for a single-operator private archive.

## Consent screen

Spring Authorization Server's default consent page (shown once per
client/user pair) is kept as-is rather than auto-approved. It is standard,
well-tested behaviour and gives one extra visible confirmation that the
connector being authorized is the one intended. Can be revisited later if it
proves annoying in practice.

## Audience validation

`validateAudienceClaim(true)` on the resource-server configuration, so a token
not issued for this specific MCP resource is rejected even if otherwise valid.
Resource Indicators (RFC 8707) are optional per the library and not required
for Claude.ai's client — not implemented in this version.

## Token identity

The token subject is the user's email (or `app_user.id`). `ArchiverMcpTools`
can now know which user is calling — opens the door to future per-user
scoping, though this version keeps tool access identical for every allowlisted
user, same as today. The improvement here is audit (which user called which
tool) and revocation (kill one person's access without rotating a secret
shared by everyone).

## Cutover

Once verified end-to-end against the real Claude.ai UI, `McpTokenFilter` and
the `MCP_TOKEN` stack secret are removed outright. No extended dual-support
period — one auth mechanism, not two running in parallel indefinitely. This is
a single-operator archive; the cutover window is one verified session, not a
staged rollout.

## Risks, stated plainly

1. **`mcp-authorization-server` / `mcp-server-security` are pre-1.0 community
   packages**, explicitly not endorsed by the Spring AI team. First
   implementation task is a spike: add the dependency, confirm it resolves
   from whatever repository it's actually published to, confirm the app boots
   with it present, *before* writing any of the real login-reuse or
   persistence logic. If the spike fails, the fallback is hand-writing the
   handful of MCP-spec endpoints directly against the stable, official
   `spring-security-oauth2-authorization-server` — more code, solid ground.
2. **The Spring Boot 4.0.2 → 4.1.x bump is a real upgrade in its own right**,
   touching the whole backend, not just MCP. The full backend test suite runs
   immediately after the bump and before any MCP-specific code, so a bump
   regression is never confused with an MCP-feature regression.
3. **No documented hook for session-based pre-authentication** at the
   Authorization Server's login step — this is genuinely new code (a filter
   analogous to `ProxyAuthFilter`), not library configuration. It needs its
   own tests, not an assumption that the library handles it.

## Testing

- Unit tests for the new session-reuse filter: trusted peer + allowlisted
  email → authenticated, no redirect. Untrusted peer, missing header, or
  non-allowlisted email → redirect to `/signin`.
- Integration test driving the full round trip: DCR registration → authorize
  (with a pre-authenticated principal, since a JUnit test cannot drive a real
  browser through Google/Apple) → token exchange with PKCE → call
  `/api/mcp/**` with the issued token → 200. Negative cases: expired/invalid
  token → 401/403, missing/incorrect PKCE verifier → rejected, non-allowlisted
  email at the authorize step → rejected, wrong-audience token → rejected.
- A real manual end-to-end pass against the actual Claude.ai UI: add the MCP
  connector fresh, complete the real OAuth flow, confirm tools work, and
  confirm a different (non-allowlisted) Google account is refused at the
  login step. This is the definition of done — passing tests alone does not
  close this out, matching how every other piece of this project's auth work
  was verified against the real deployment before being called complete.

## Out of scope

- Resource Indicators (RFC 8707) — optional, not needed for Claude.ai.
- Per-client DCR scoping/restriction — accepted limitation, mitigated by the
  login-step allowlist gate instead.
- Refresh token rotation policy tuning — use Spring AS defaults for this
  version.
- Multi-key JWT signing / key rotation — single key is sufficient here.
