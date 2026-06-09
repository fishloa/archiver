# Add `archive.czernin.eu` as a co-primary external host

**Date:** 2026-06-09
**Status:** Approved design, ready for implementation plan

## Goal

Serve the archiver at **`archive.czernin.eu`** in addition to the existing
`archiver.icomb.place`. `archive.czernin.eu` becomes the **canonical / new-primary**
public identity (self-links, docs, MCP responses point there), while
`archiver.icomb.place` keeps working unchanged. Both **Google and Apple** login must
work on the new domain.

## Key facts established by inspection

### Internal web container (`web/nginx.conf`) — already host-agnostic
- `server_name _;` accepts any host.
- Every `proxy_pass` sets `Host $host` (the real incoming host) and
  `X-Forwarded-Proto $scheme`. No hostname is hardcoded.
- Conclusion: the internal proxy needs only one additive change
  (forward `X-Forwarded-Host` to the oauth2-proxy locations).

### Auth is the hard part (two oauth2-proxy instances, cookie-based)
`deploy/docker-compose.yml`:
- `oauth2-proxy-google` and `oauth2-proxy-apple` each hardcode:
  - `OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place`
  - `OAUTH2_PROXY_REDIRECT_URL: https://archiver.icomb.place/oauth2-*/callback`
- Cookies are domain-scoped: an `archiver.icomb.place` cookie cannot be read on
  `archive.czernin.eu` (no shared parent). Each domain must run its own full OAuth
  round-trip and set its own cookie.

### SvelteKit CSRF
- `frontend` service hardcodes `ORIGIN: https://archiver.icomb.place`.
- adapter-node uses `ORIGIN` to validate the `Origin` header on POSTs. A form POST
  arriving from `archive.czernin.eu` would be **rejected (403)**.

### Zelkova host nginx + TLS (verified on the box)
- Host nginx (`/etc/nginx`, system service, NOT Docker) terminates TLS on :443 and
  proxies to `localhost:8099` (web container).
- vhost `archiver.icomb.place` includes `snippets/ssl-icomb.place.conf` and
  `snippets/proxy-ws.conf` (which sets `Host $host`, `X-Forwarded-Proto $scheme`).
- **One multi-SAN cert** named `icomb.place` covers
  `*.icomb.place`, `icomb.place`, `gptrader.live`, `www.gptrader.live`.
  `gptrader.live` (a different apex) rides on this cert — the exact pattern
  `archive.czernin.eu` will follow.
- Cert issued via **`certbot --dns-cloudflare`** (`/root/cloudflare.ini`,
  legacy email+global-key). Renewal conf already uses DNS-01.
- **`czernin.eu` is active in the same Cloudflare account** → DNS-01 can create
  `_acme-challenge.archive.czernin.eu` automatically; no DNS-provider change.
- `archiver.icomb.place` DNS = **DNS-only (grey-cloud) CNAME → `gateway.icomb.place`**.
  Cloudflare proxy is OFF (it would break SSE + long oauth timeouts).

## Design

### A. In-repo changes

**A1. `deploy/docker-compose.yml` — both oauth2-proxy services**
For `oauth2-proxy-google` and `oauth2-proxy-apple`:
- `OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place,archive.czernin.eu`
  (oauth2-proxy selects the cookie domain matching the request host).
- Add `OAUTH2_PROXY_REVERSE_PROXY: "true"` (trust `X-Forwarded-*`).
- **Remove** `OAUTH2_PROXY_REDIRECT_URL`. With reverse-proxy mode and no fixed
  redirect, the callback is derived per-request as
  `<proto>://<forwarded-host>/oauth2-*/callback` — login on czernin returns to
  czernin, login on icomb returns to icomb.
- Add `OAUTH2_PROXY_WHITELIST_DOMAINS: archiver.icomb.place,archive.czernin.eu`
  (safety for any absolute post-login redirect).

**A2. `deploy/docker-compose.yml` — frontend service**
- Remove `ORIGIN: https://archiver.icomb.place`.
- Add `PROTOCOL_HEADER: x-forwarded-proto` and `HOST_HEADER: host` so adapter-node
  derives the origin per-request (web nginx already supplies both headers).

**A3. `web/nginx.conf`**
- Add `proxy_set_header X-Forwarded-Host $host;` to the oauth2 location blocks
  (`/oauth2-google/`, `= /oauth2-google/auth`, `/oauth2-apple/`,
  `= /oauth2-apple/auth`) so oauth2-proxy sees the real public host.

**A4. Canonical self-references → czernin (new primary)**
- `backend/.../mcp/ArchiverMcpTools.java`: 3 hardcoded `https://archiver.icomb.place/api`
  → `https://archive.czernin.eu/api`.
- `CLAUDE.md` (machine-readable API base URL), `web/validate-deploy.sh`,
  `web/test-endpoints.sh` defaults, `ingest-local/ingest.py` (`BACKEND_URL` + the
  "View at" link) → czernin. All remain parameterizable; icomb still works.

### B. External / manual

**B1. DNS (Cloudflare API, `czernin.eu` zone)**
- Create CNAME `archive.czernin.eu` → `gateway.icomb.place`, **proxied = false**
  (mirror archiver.icomb.place).

**B2. Cert (zelkova) — expand the existing SAN cert**
```bash
sudo certbot certonly --cert-name icomb.place \
  --dns-cloudflare --dns-cloudflare-credentials /root/cloudflare.ini \
  --dns-cloudflare-propagation-seconds 30 \
  -d '*.icomb.place' -d icomb.place -d gptrader.live -d www.gptrader.live \
  -d archive.czernin.eu
```
Must re-list all existing names plus the new one. Renewal conf is unchanged
(already DNS-01), so future renewals keep czernin covered.

**B3. Host nginx vhost (`/etc/nginx/sites-enabled/archive.czernin.eu`)**
- Clone `archiver.icomb.place`; set `server_name archive.czernin.eu;`.
- Keep `include snippets/ssl-icomb.place.conf;` (now covers czernin).
- Keep the SSE / MCP / api / swagger / actuator / `/` locations identical.
- Add a static location for Apple domain verification:
  `location = /.well-known/apple-developer-domain-association.txt` serving the file
  Apple provides (served directly by host nginx, no container rebuild).
- `sudo nginx -t && sudo nginx -s reload`.

**B4. Google Cloud console**
- Add authorized redirect URI `https://archive.czernin.eu/oauth2-google/callback`.

**B5. Apple Developer**
- Add Return URL `https://archive.czernin.eu/oauth2-apple/callback` to the Service ID.
- Register the `archive.czernin.eu` domain and place the
  `apple-developer-domain-association.txt` so Apple's verification fetch succeeds
  (see B3).

### C. Sequencing (protects the live icomb login)

The only change that touches the *currently-working* icomb auth is A1 (swapping the
fixed redirect for host-derived). Sequence to de-risk:

1. **B1** DNS CNAME.
2. **B2 + B3** cert expand + vhost + reload (czernin now serves over TLS).
3. **B4 + B5** OAuth consoles + Apple domain verification.
4. **A1–A4** deploy repo changes **last** (Portainer redeploy). Keep the old
   `OAUTH2_PROXY_REDIRECT_URL` / `ORIGIN` lines noted for one-line rollback.

### D. Verification

- `curl -sI https://archive.czernin.eu/` → 200/302 with a **valid cert**.
- `curl -s https://archive.czernin.eu/api/records/events -H "Accept: text/event-stream" --max-time 3`
  → `:connected`.
- Browser: full **Google** login on czernin → returns to czernin, authenticated,
  cookie scoped to `archive.czernin.eu`.
- Browser: full **Apple** login on czernin → same.
- A form POST on czernin (e.g. profile save) succeeds (CSRF passes).
- **Regression:** `archiver.icomb.place` Google + Apple login still work.

## Testing note

This is config/infra, not application logic — the OAuth + multi-domain + host-nginx
behavior cannot be exercised by Testcontainers. Verification is the manual
end-to-end checklist in section D. The only change with a unit-testable surface is
the MCP self-link constant (A4).

## Risks

- **Breaking icomb auth via A1.** Mitigated by sequencing (A1 last) and keeping the
  old env values as rollback. The header chain is verified: host nginx sends
  `Host $host`; web nginx forwards `Host $host` + (new) `X-Forwarded-Host $host` to
  oauth2-proxy, which with `reverse-proxy=true` derives the correct callback.
- **Cloudflare proxy accidentally enabled** on the new CNAME would break SSE / oauth
  timeouts — must be created grey-cloud (proxied=false).
- **certbot expand omitting an existing SAN** would silently drop coverage for that
  name — the command re-lists all names explicitly.
