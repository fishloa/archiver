# Archive auth lockdown — design

Date: 2026-07-27
Status: approved, pending implementation
Branch: `feat/archive-czernin-host`

## Goal

No anonymous access to the archive. Every request is attributable to an email
address on an allowlist that the site owner controls through the existing
`/admin/users` UI. Apple ID sign-in works on `archive.czernin.eu` as well as
`archiver.icomb.place`.

## Current state (verified 2026-07-27)

| Layer | Behaviour today |
|---|---|
| host nginx (zelkova) | TLS terminate, forward to `localhost:8099`. No auth, no header sanitising. |
| backend network | on `archiver_default` **and** `ipvlan-lan` — port 8080 answers directly on the LAN at `192.168.19.0`. Reachability stays; identity trust becomes proxy-only. |
| web nginx `/` | `auth_request` → falls through to `@anon` on 401 |
| web nginx `/api/` | `auth_request` → falls through to `@api_anon` on 401 |
| web nginx `/api/mcp/` | no `auth_request` |
| web nginx `/api/(records\|processor)/events` | no `auth_request` |
| oauth2-proxy (google, apple) | `EMAIL_DOMAINS: "*"` — any account gets a session |
| backend `SecurityConfig` | `/api/**` GET `permitAll()`, `anyRequest().permitAll()` |
| allowlist | `app_user` + `app_user_email`, managed at `/admin/users` — **exists and works** |

The allowlist infrastructure is already built. The problem is that nothing
enforces it: an email with no `app_user_email` row gets no role, and
`permitAll()` admits it anyway.

## Phase 0 — header spoofing fix

**Build this first. Ships with Phase 1, not separately.**

### The defect

`ProxyAuthFilter.java:29` derives identity from the `X-Auth-Email` request
header and grants `ROLE_ADMIN` from it (`:47`). That is safe only if nginx
guarantees the header is never client-controlled.

nginx overrides it in `/api/` (`nginx.conf:156`) and `/` (`:181`). It does not
override it in:

- `@api_anon` (`:160`) — where every unauthenticated `/api/` request lands
- `/api/mcp/` (`:113`)
- `/api/(records|processor)/events` (`:128`)

nginx inherits `proxy_set_header` from the enclosing block only when the
current block declares none of its own. All three locations declare some, so
the server-level headers do not apply and the client's `X-Auth-Email` is
forwarded verbatim. The host nginx does not strip it either.

Confirmed against production:

```
$ curl -o /dev/null -w '%{http_code}' https://archiver.icomb.place/api/admin/users
403
$ curl -H 'X-Auth-Email: timothy.corbettclark@gmail.com' \
       https://archiver.icomb.place/api/admin/users
[{"id":2,"display_name":"Alex Fishlock","role":"admin", ...}]
```

Any unauthenticated caller with one allowlisted address gets admin. Reads and
writes both — including `POST /api/admin/**`, which can mint new admins.
`GET /api/admin/users` leaks the address list needed to bootstrap.

The backend is also attached to `ipvlan-lan` (`192.168.19.0`), which it needs to
reach PostgreSQL at `192.168.19.130`, so port 8080 answers directly on the LAN
with no nginx in the path, and the same spoof works there:

```
$ curl -H 'X-Auth-Email: timothy.corbettclark@gmail.com' \
       http://192.168.19.0:8080/api/admin/users
[{"id":2,"display_name":"Alex Fishlock","role":"admin", ...}]
```

A proxy-side fix cannot reach that path. The trust decision has to live in the
backend.

Retained access logs show no prior exploitation: the only `/api/admin` hits are
the two probes above. The log format captures no request headers, so spoofing
against other routes cannot be ruled out.

### Threat model

WAN is the threat. The LAN is trusted, for now, both for reachability and for
identity. The backend stays on `ipvlan-lan`, Spring is not bound to a single
interface, and `X-Auth-Email` is honoured from the reverse proxy *and* from any
LAN peer.

The consequence, stated plainly: after this ship, anything on
`192.168.16.0/22` can still assert any identity, including admin, by calling
`http://192.168.19.0:8080` with a chosen `X-Auth-Email`. That is a deliberate,
temporary acceptance — it keeps local tooling and the existing test suite
working. Narrowing the trusted set to the proxy alone is a follow-up ship, and
the config is shaped so that it is a one-line change when you want it.

What this ship does close is the WAN path, which is the whole objective.

### Fix

Two independent layers; both required.

1. **Proxy** — add `proxy_set_header X-Auth-Email "";` to `@api_anon`,
   `/api/mcp/`, and the events location. Phase 1 deletes `@api_anon`, but the
   other two survive, and the explicit clear documents the invariant for any
   location added later.

2. **Backend — trusted peer check.** `ProxyAuthFilter` honours `X-Auth-Email`
   only when the request's TCP peer is in a configured trusted set. No shared
   secret.

   The check uses `request.getRemoteAddr()` — the actual TCP peer — and must
   never consult `X-Forwarded-For` or `X-Real-IP`, both of which are
   client-controlled and would reintroduce the same class of defect.

   Two kinds of entry in the trusted set:

   - **Hostname**, `archiver.auth.trusted-proxy-hosts`, default `web`. The
     proxy's address is dynamic — `archiver-web-1` is `10.0.9.16` today and
     changes on redeploy — so a pinned IP would rot. The filter resolves the
     name through Docker's embedded DNS (`127.0.0.11`) and caches the result
     ~30s, so it self-heals across redeploys without a lookup per request.
   - **CIDR**, `archiver.auth.trusted-cidrs`, default
     `127.0.0.1/32, ::1/128, 10.0.9.0/24, 192.168.16.0/22` — loopback, the
     `archiver_default` container network, and the trusted LAN. Loopback costs
     nothing in production: only a process inside the backend container can be a
     loopback peer.

     `10.0.9.0/24` is **required, not a convenience**. SvelteKit's server side
     calls `BACKEND_URL: http://backend:8080` directly, container-to-container,
     forwarding `X-Auth-Email` (`frontend/src/lib/server/api.ts:10`,
     `frontend/src/routes/translate/+server.ts:25`). The peer is the frontend
     container (`10.0.9.9`), not the web container. Without this entry every
     authenticated page in the UI breaks.

   A peer matching either is trusted. Anything else — which is every WAN client
   — has `X-Auth-Email` discarded and proceeds anonymous. Fail closed: an
   unresolvable hostname or malformed CIDR drops that entry, it never widens the
   set.

   Narrowing to proxy-only later means emptying `trusted-cidrs`. Keep the two
   settings independent so that is a config change, not a code change.

   **Existing tests need no changes.** `AdminControllerTest`,
   `AuthControllerTest`, `ProfileControllerTest` and `AdminPipelineControllerTest`
   send `X-Auth-Email` from a local `HttpClient`; loopback is in the default
   trusted set, so they keep passing as written. No `application-test.yml` edit.

   The one exception is the new spoofing regression test, which must override
   `trusted-cidrs` to empty and `trusted-proxy-hosts` to a name that does not
   resolve to loopback — otherwise it passes vacuously and proves nothing.

### Residual risk of peer-IP trust

- **Any LAN host can assert any identity.** Accepted for now, per the threat
  model. This is the largest residual item and the reason the follow-up ship
  exists. Emptying `trusted-cidrs` closes it.
- **Any container on `archiver_default` can do the same** — workers, scrapers,
  TEI. They do not need `X-Auth-Email` (they authenticate with the processor
  Bearer token) but the CIDR grants it to them anyway, because it cannot
  distinguish them from the frontend by address. Narrowing this means trusting
  `web` and `frontend` by hostname and dropping `10.0.9.0/24`, which is the
  cleaner end state but a larger change than this ship.
- **Compromise of the web container.** Unavoidable; asserting identity is its job.
- **Docker address reuse.** If `web` is recreated and its old address goes to
  another container inside the DNS cache window, the cache is briefly wrong.
  Bounded by the ~30s TTL. Keep the TTL short; do not cache negative results.

Layer 1 still earns its place: it stops a spoofed header entering the network at
all, and keeps the WAN path safe if trusted-peer resolution ever fails open
through a bug.

### Tests

- `ProxyAuthFilter` unit test: peer in the trusted set → authenticated; peer not
  in the set, hostname unresolvable, empty set → anonymous.
- `ProxyAuthFilter` unit test: peer untrusted but `X-Forwarded-For` set to a
  trusted address → still anonymous. Guards against the header being consulted.
- Integration test: `GET /api/admin/users` with a spoofed `X-Auth-Email` from an
  untrusted peer returns 403. This is the regression test for the defect above
  and must fail against current `main`.
- `ProxyAuthFilter` unit test: peer in `trusted-cidrs` but not the resolved
  hostname → authenticated, and vice versa. Both entry kinds work independently.
- `web/validate-deploy.sh`: spoofed-header probe through nginx from the WAN side
  asserting non-200. Do **not** assert anything about the backend's LAN address —
  spoofing there is expected to succeed under the current threat model, and a
  test asserting otherwise would fail. Add it when `trusted-cidrs` is emptied.

## Phase 1 — deny by default

### nginx

- Delete `@anon` and `@api_anon`, and the server-level `error_page 401 = @anon`.
- Browser routes: `error_page 401 = /signin` so an unauthenticated visitor gets
  the existing sign-in page (`web/signin.html`, already offers both Google and
  Apple).
- API routes: 401 returns JSON, no redirect — the SvelteKit client must not
  receive HTML where it expects an API response.
- `/api/records/events` gains `auth_request`.
- `/api/processor/events` stays unauthenticated at nginx; `ProcessorTokenFilter`
  guards it with the Bearer token and the workers depend on it.
- New unauthenticated location for `/.well-known/` — required for Apple's
  domain verification fetch in Phase 2 below. Serve from disk, never proxy to
  the frontend.

### Backend `SecurityConfig`

Invert the default and remove the blanket read permit:

- `anyRequest()` → `.authenticated()` (from `permitAll()`, `:104`)
- drop `GET /api/**` `permitAll()` (`:51`)
- drop `POST /api/search/semantic` `permitAll()` (`:66`)
- drop `POST /api/translate` `permitAll()` (`:72`)
- drop `POST /api/family-tree/reload` and `/invalidate-matches` `permitAll()`
  (`:75`, `:77`) — these are unauthenticated write endpoints today
- keep `/api/auth/**` `permitAll()` — `/api/auth/me` must answer for a signed-out
  caller
- keep `/api/mcp/**` as its own matcher, see Phase 3
- verify `/actuator/**` and `/swagger-ui` are not exposed by the new
  `anyRequest().authenticated()` in a way that breaks container health checks

### `ProxyAuthFilter` — the unknown-user case

An email that authenticates with Google or Apple but has no `app_user_email`
row currently yields an anonymous context. Under deny-by-default that becomes a
403, and the browser flow redirects to `/signin`, which sees a valid oauth2
cookie and bounces back — an infinite redirect for anyone not on the list.

The filter must distinguish *not signed in* from *signed in but not allowed*.
Signed-in-but-unknown gets a distinct response that `/signin` and the frontend
render as "signed in as X, not on the access list", with a sign-out link.

### Residual risk, accepted

oauth2-proxy keeps `EMAIL_DOMAINS: "*"`, so any Google or Apple account can
still obtain a session cookie. The cookie alone grants nothing — the allowlist
is enforced one layer in, at the backend, where the admin UI already lives.
Moving enforcement to the proxy would mean `AUTHENTICATED_EMAILS_FILE` and SSH
to edit the list. Rejected.

### Tests

- Integration: unauthenticated `GET /api/records` → 401/403, not 200.
- Integration: allowlisted email + valid proxy secret → 200.
- Integration: signed-in-but-unknown email → distinct status, not a redirect loop.
- `web/test-endpoints.sh`: assert no route returns 200 without auth.

## Phase 2 — Apple ID on archive.czernin.eu

Credentials already exist and work for `archiver.icomb.place`: Team `5DJJ367BH4`,
Key `JJ8JU2288F`, Services ID `com.icomb.place.archiver.signin`, p8 key in the
stack env as `APPLE_KEY_P8_B64`. `archive.czernin.eu` is **not** yet on Apple's
domain list. No new code — portal config plus a stack update.

### Apple Developer portal

1. Certificates, IDs & Profiles → Identifiers → filter **Services IDs** →
   `com.icomb.place.archiver.signin`.
2. Configure → Sign in with Apple → Web Authentication Configuration.
3. **Domains and Subdomains**: add `archive.czernin.eu`, keep the existing entry.
4. **Return URLs**: add `https://archive.czernin.eu/oauth2-apple/callback`, keep
   the existing entry.
5. Download `apple-developer-domain-association.txt` and serve it at
   `https://archive.czernin.eu/.well-known/apple-developer-domain-association.txt`,
   unauthenticated. Apple's fetcher must not receive the sign-in redirect — this
   is what the `/.well-known/` location in Phase 1 is for. Deploy before verifying.
6. Verify, then Save.

### Google Cloud Console

Add `https://archive.czernin.eu/oauth2-google/callback` to the OAuth client's
authorised redirect URIs (client `840118678161-rgs82...`).

### Stack deploy

Portainer stack #183 is running an **older compose than the repo**. Commit
`6ae7700` already added, for both proxies:

- `COOKIE_DOMAINS: archiver.icomb.place,archive.czernin.eu`
- `WHITELIST_DOMAINS: archiver.icomb.place,archive.czernin.eu`
- `REVERSE_PROXY: "true"`
- removal of the hardcoded `REDIRECT_URL`, so the callback follows
  `X-Forwarded-Host`
- frontend `PROTOCOL_HEADER`/`HOST_HEADER` in place of a fixed `ORIGIN`

The live stack still has single-domain values and hardcoded redirect URLs.
Deploying the repo compose is a prerequisite for Apple or Google sign-in to
work on the second host at all.

When PUT-ing the stack, include `"Webhook": "b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71"`
or it gets cleared. Phase 0 adds no new env vars — the trusted-proxy hostname
defaults to `web` and needs no stack change.

### Verification

Sign in end-to-end on both hosts with both providers — four combinations — in a
clean browser profile. Confirm the session cookie is scoped so that signing in
on one host does not silently authorise the other unless intended.

## Phase 3 — MCP

`/api/mcp/**` is the largest anonymous surface and is in active use: the access
log shows `POST /api/mcp/sse` from `Claude-User`.

### Phase 3a, this ship — static bearer token

Require a secret token on `/api/mcp/`. Claude.ai custom connectors can send a
custom header. `SecurityConfig` swaps `permitAll()` on `/api/mcp/**` for a token
check. Anonymous access reaches zero when this ships.

Tighten the CORS block at `SecurityConfig.java:110-120` at the same time —
`allowedOrigins: ["*"]` with `allowedHeaders: ["*"]` should not outlive the
`permitAll()` it was written for.

### Phase 3b, separate spec — OAuth

Federate to the Google and Apple oauth2-proxies already deployed rather than
standing up a second identity source. Needs
`/.well-known/oauth-protected-resource`, `/.well-known/oauth-authorization-server`,
dynamic client registration (RFC 7591), an authorize endpoint delegating to the
existing proxies, a token endpoint, and Bearer validation mapping the token
subject back to `app_user`. Spring Authorization Server on Spring Boot 4.0 with
Spring AI 2.0.0-M2 is unproven here. Own spec, own plan.

## Follow-up ships

- **Narrow the trusted set to named containers.** Drop both `192.168.16.0/22` and
  `10.0.9.0/24`, and set `trusted-proxy-hosts: web,frontend` instead. Closes LAN
  identity spoofing and stops workers being trusted for something they never
  needed. Mostly config, plus the deferred LAN assertion in
  `validate-deploy.sh`. Verify the frontend resolves to a stable name first.
- **MCP OAuth** — Phase 3b below, own spec.

## Out of scope

- Ingesting the 20 scanned pages found untracked in the repo root — deleted.
- Removing the other untracked junk (`P`, `pages/`, `page-index.json`,
  `frontend/pages/`) and adding `.gitignore` entries.
- Any change to `ProcessorTokenFilter` or the worker/scraper token flow.

## Ship order

Phase 0 built first, then 1, 2, 3a. All four deploy together — the site must not
be left in a half-locked state, and Phase 1 without Phase 0 still trusts a
client-supplied header on `/api/mcp/`.

Full local checks before push: `./gradlew test` and `./gradlew compileJava` with
`JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current`, `npm run check` in
`frontend/`. Then CI green, then Portainer, then `make validate-deploy` against
both hosts.
