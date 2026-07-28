# archive.czernin.eu Co-Primary Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the archiver at `archive.czernin.eu` (new canonical host) alongside `archiver.icomb.place`, with Google + Apple login working on both.

**Architecture:** The internal web-container nginx is already host-agnostic. We make auth (oauth2-proxy) derive its OAuth callback from the request host, make SvelteKit CSRF derive its origin from request headers, expand the existing Cloudflare DNS-01 SAN cert to add the new name, and add a host-nginx vhost. Repo (Portainer) changes deploy **last** so the live icomb auth is never broken without an instant rollback.

**Tech Stack:** oauth2-proxy, nginx, certbot (dns-cloudflare), Cloudflare API, SvelteKit adapter-node, Spring Boot (MCP), Portainer.

---

## Reference values (verified 2026-06-09)

- Zelkova SSH alias: `zelkova` (host nginx is the **system** nginx, not Docker).
- Cloudflare creds on zelkova: `/root/cloudflare.ini` (legacy `dns_cloudflare_email` + `dns_cloudflare_api_key`).
- Existing SAN cert `icomb.place`: `*.icomb.place icomb.place gptrader.live www.gptrader.live`.
- `archiver.icomb.place` DNS = grey-cloud CNAME → `gateway.icomb.place`.
- Portainer archiver redeploy webhook: `https://docker.icomb.place/api/stacks/webhooks/b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71`.
- `czernin.eu` zone is active in the same Cloudflare account.

**Sequencing rule:** Tasks 1→7 in order. Task 7 (Portainer redeploy) is the only one that touches live icomb auth — do it last and keep the rollback note handy.

---

## Task 1: DNS record for archive.czernin.eu

**Files:** none in repo — Cloudflare API call run from local Mac or zelkova.

- [ ] **Step 1: Create the CNAME (grey-cloud) in the `czernin.eu` zone**

Run on zelkova (has the creds), mirroring `archiver.icomb.place`:

```bash
ssh zelkova 'EMAIL=$(sudo grep -i dns_cloudflare_email /root/cloudflare.ini | sed "s/.*=\s*//"); \
KEY=$(sudo grep -i dns_cloudflare_api_key /root/cloudflare.ini | sed "s/.*=\s*//"); \
ZID=$(curl -s "https://api.cloudflare.com/client/v4/zones?name=czernin.eu" -H "X-Auth-Email: $EMAIL" -H "X-Auth-Key: $KEY" | python3 -c "import sys,json;print(json.load(sys.stdin)[\"result\"][0][\"id\"])"); \
curl -s -X POST "https://api.cloudflare.com/client/v4/zones/$ZID/dns_records" \
  -H "X-Auth-Email: $EMAIL" -H "X-Auth-Key: $KEY" -H "Content-Type: application/json" \
  --data "{\"type\":\"CNAME\",\"name\":\"archive.czernin.eu\",\"content\":\"gateway.icomb.place\",\"proxied\":false,\"ttl\":1}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(\"success:\", d.get(\"success\"), \"errors:\", d.get(\"errors\"))"'
```

Expected: `success: True errors: []`

- [ ] **Step 2: Verify resolution**

Run: `dig +short archive.czernin.eu`
Expected: resolves (CNAME → gateway.icomb.place → an IP). May take up to a minute.

No commit (no repo change).

---

## Task 2: Expand the SAN cert to cover archive.czernin.eu

**Files:** none in repo — certbot on zelkova.

- [ ] **Step 1: Expand the existing cert (re-list ALL names + the new one)**

```bash
ssh zelkova "sudo certbot certonly --cert-name icomb.place \
  --dns-cloudflare --dns-cloudflare-credentials /root/cloudflare.ini \
  --dns-cloudflare-propagation-seconds 30 --non-interactive \
  -d '*.icomb.place' -d icomb.place -d gptrader.live -d www.gptrader.live \
  -d archive.czernin.eu"
```

Expected: `Successfully received certificate.` and the cert path unchanged (`/etc/letsencrypt/live/icomb.place/fullchain.pem`).

- [ ] **Step 2: Verify the new SAN is present**

Run: `ssh zelkova "sudo certbot certificates | grep -A2 'Certificate Name: icomb.place'"`
Expected: `Domains:` line now includes `archive.czernin.eu`.

No commit.

---

## Task 3: Host nginx vhost for archive.czernin.eu

**Files (on zelkova):**
- Create: `/etc/nginx/sites-enabled/archive.czernin.eu`
- Create dir: `/var/www/well-known/archive.czernin.eu/` (for the Apple file, populated in Task 6)

- [ ] **Step 1: Create the vhost (clone of archiver.icomb.place + server_name + Apple .well-known)**

```bash
ssh zelkova "sudo tee /etc/nginx/sites-enabled/archive.czernin.eu >/dev/null" <<'EOF'
server {
    server_name archive.czernin.eu;
    include snippets/ssl-icomb.place.conf;

    access_log /var/log/nginx/archive.czernin.eu.access.log timed_combined;
    error_log  /var/log/nginx/archive.czernin.eu.error.log warn;

    client_max_body_size 200m;

    # Apple Sign In domain verification (served directly, no backend)
    location = /.well-known/apple-developer-domain-association.txt {
        alias /var/www/well-known/archive.czernin.eu/apple-developer-domain-association.txt;
        default_type text/plain;
    }

    # SSE endpoints — long-lived connections
    location /api/records/events {
        include snippets/proxy-ws.conf;
        proxy_pass http://localhost:8099;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 30m;
        proxy_set_header Connection "";
    }

    location /api/mcp/sse {
        include snippets/proxy-ws.conf;
        proxy_pass http://localhost:8099;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400;
        proxy_set_header Connection "";
    }

    location /api/ {
        include snippets/proxy-ws.conf;
        proxy_pass http://localhost:8099;
        proxy_buffering off;
    }

    location /api-docs   { include snippets/proxy-ws.conf; proxy_pass http://localhost:8099; }
    location /swagger-ui { include snippets/proxy-ws.conf; proxy_pass http://localhost:8099; }
    location /actuator/  { include snippets/proxy-ws.conf; proxy_pass http://localhost:8099; }

    location / {
        include snippets/proxy-ws.conf;
        proxy_pass http://localhost:8099;
    }
}
EOF
```

- [ ] **Step 2: Create the well-known dir with a placeholder so nginx config test passes**

```bash
ssh zelkova "sudo mkdir -p /var/www/well-known/archive.czernin.eu && \
echo 'placeholder' | sudo tee /var/www/well-known/archive.czernin.eu/apple-developer-domain-association.txt >/dev/null"
```

- [ ] **Step 3: Validate and reload nginx (also loads the expanded cert)**

Run: `ssh zelkova "sudo nginx -t && sudo nginx -s reload"`
Expected: `nginx: configuration file ... test is successful` and a clean reload.

- [ ] **Step 4: Verify TLS + the app respond on the new host**

```bash
curl -sI https://archive.czernin.eu/ --max-time 10 | head -1
curl -sv https://archive.czernin.eu/ --max-time 10 2>&1 | grep -i 'subject\|issuer\|SSL certificate verify'
curl -s https://archive.czernin.eu/api/records/events -H "Accept: text/event-stream" --max-time 3
```
Expected: HTTP 200/302; cert verifies OK; SSE returns `:connected`.

No commit (changes live on zelkova, not in repo).

---

## Task 4: Repo — make auth + CSRF host-aware (NOT yet deployed)

**Files:**
- Modify: `deploy/docker-compose.yml` (oauth2-proxy-google, oauth2-proxy-apple, frontend)
- Modify: `web/nginx.conf` (oauth2 location blocks)

- [ ] **Step 1: Edit `oauth2-proxy-google` env (`deploy/docker-compose.yml`)**

Replace:
```yaml
      OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place
```
with:
```yaml
      OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place,archive.czernin.eu
      OAUTH2_PROXY_WHITELIST_DOMAINS: archiver.icomb.place,archive.czernin.eu
      OAUTH2_PROXY_REVERSE_PROXY: "true"
```
and **delete** the line:
```yaml
      OAUTH2_PROXY_REDIRECT_URL: https://archiver.icomb.place/oauth2-google/callback
```

- [ ] **Step 2: Edit `oauth2-proxy-apple` env (same file)**

Replace:
```yaml
      OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place
```
with:
```yaml
      OAUTH2_PROXY_COOKIE_DOMAINS: archiver.icomb.place,archive.czernin.eu
      OAUTH2_PROXY_WHITELIST_DOMAINS: archiver.icomb.place,archive.czernin.eu
      OAUTH2_PROXY_REVERSE_PROXY: "true"
```
and **delete**:
```yaml
      OAUTH2_PROXY_REDIRECT_URL: https://archiver.icomb.place/oauth2-apple/callback
```

- [ ] **Step 3: Edit `frontend` env (same file) — dynamic CSRF origin**

Replace:
```yaml
      ORIGIN: https://archiver.icomb.place
```
with:
```yaml
      PROTOCOL_HEADER: x-forwarded-proto
      HOST_HEADER: host
```

- [ ] **Step 4: Edit `web/nginx.conf` — forward X-Forwarded-Host to oauth2-proxy**

In each of these four blocks — `location /oauth2-google/`, `location = /oauth2-google/auth`, `location /oauth2-apple/`, `location = /oauth2-apple/auth` — add this line directly after the existing `proxy_set_header Host $host;`:
```nginx
        proxy_set_header X-Forwarded-Host $host;
```

- [ ] **Step 5: Sanity-check the compose file parses**

Run: `cd /Volumes/External/Projects/archiver && docker compose -f deploy/docker-compose.yml config -q && echo OK`
Expected: `OK` (no YAML errors). (Env-var interpolation warnings are fine.)

- [ ] **Step 6: Commit**

```bash
git add deploy/docker-compose.yml web/nginx.conf
git commit -m "feat: make oauth2-proxy + svelte CSRF host-aware for multi-domain"
```

---

## Task 5: Repo — point canonical self-references at archive.czernin.eu

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java` (3 sites)
- Modify: `CLAUDE.md`, `web/validate-deploy.sh`, `web/test-endpoints.sh`, `ingest-local/ingest.py`

- [ ] **Step 1: Update MCP self-links**

In `ArchiverMcpTools.java`, replace all three occurrences of the literal
`https://archiver.icomb.place/api` with `https://archive.czernin.eu/api`.

Run: `cd /Volumes/External/Projects/archiver && grep -rn "archiver.icomb.place/api" backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`
Expected after edit: no matches (all three replaced).

- [ ] **Step 2: Update docs + script default URLs**

Replace `https://archiver.icomb.place` with `https://archive.czernin.eu` in:
- `CLAUDE.md` — the Machine-Readable API base URL line (`Base URL:` and the `/api/v1` table intro).
- `web/validate-deploy.sh` and `web/test-endpoints.sh` — the default `BASE=` value (keep the `$1` override).
- `ingest-local/ingest.py` — `BACKEND_URL` constant and the `print(f"View at: ...")` line.

- [ ] **Step 3: Compile backend to confirm the Java edit is valid**

Run: `cd /Volumes/External/Projects/archiver/backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew compileJava -q && echo OK`
Expected: `OK`.

- [ ] **Step 4: Apply Java formatting (Google Java Format)**

Run: `cd /Volumes/External/Projects/archiver/backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply -q`
Expected: completes; `git diff --stat` shows only intended changes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java CLAUDE.md web/validate-deploy.sh web/test-endpoints.sh ingest-local/ingest.py
git commit -m "feat: make archive.czernin.eu the canonical host in self-links and docs"
```

---

## Task 6: OAuth provider consoles (manual — user action required)

**Files:** none. These are web-console actions the user must perform; agent provides exact values and verifies afterward.

- [ ] **Step 1: Google — add the new redirect URI**

In Google Cloud Console → APIs & Services → Credentials → the archiver OAuth 2.0 Client → **Authorized redirect URIs**, add:
```
https://archive.czernin.eu/oauth2-google/callback
```
Leave the existing icomb URI in place. Save.

- [ ] **Step 2: Apple — add Return URL + register domain**

In Apple Developer → Certificates, IDs & Profiles → Identifiers → the archiver **Services ID** → Sign In with Apple → Configure:
- Add **Website URL** domain: `archive.czernin.eu` and Return URL `https://archive.czernin.eu/oauth2-apple/callback`.
- **Download** the `apple-developer-domain-association.txt` Apple generates.

- [ ] **Step 3: Place the Apple association file on zelkova**

Replace the placeholder created in Task 3 with the real file content:
```bash
# from the dir containing the downloaded file:
scp apple-developer-domain-association.txt zelkova:/tmp/aa.txt
ssh zelkova "sudo mv /tmp/aa.txt /var/www/well-known/archive.czernin.eu/apple-developer-domain-association.txt"
```
Verify it serves:
```bash
curl -s https://archive.czernin.eu/.well-known/apple-developer-domain-association.txt | head -c 80
```
Expected: the JSON/association content (not `placeholder`).

- [ ] **Step 4: Complete Apple verification**

Back in the Apple Service ID config, click **Verify** for `archive.czernin.eu`. Expected: domain shows verified. Save.

No commit.

---

## Task 7: Deploy repo changes + full verification (the live-auth-touching step)

**Files:** none — Portainer redeploy + verification.

- [ ] **Step 1: Push the branch and let CI build/deploy**

```bash
cd /Volumes/External/Projects/archiver && git push -u origin feat/archive-czernin-host
```
Then either open a PR to `main` and merge (CI builds changed images and triggers the Portainer webhook on success), or — if deploying the branch directly is the established flow — trigger redeploy:
```bash
curl -X POST https://docker.icomb.place/api/stacks/webhooks/b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71
```
Wait ~20s for containers to restart.

- [ ] **Step 2: Verify oauth2-proxy + frontend came up clean**

Run: `ssh zelkova "docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'oauth2-proxy|frontend|web'"`
Expected: all `Up`, none `Restarting`.

- [ ] **Step 3: Verify new host — Google login round-trip (browser)**

In a clean/incognito browser: visit `https://archive.czernin.eu/` → Sign in → choose Google → complete. Expected: returns to `https://archive.czernin.eu/...` authenticated; the `_oauth2_proxy` cookie is scoped to `archive.czernin.eu` (check devtools → Application → Cookies).

- [ ] **Step 4: Verify new host — Apple login round-trip (browser)**

Same, choosing Apple. Expected: returns to `https://archive.czernin.eu/...` authenticated.

- [ ] **Step 5: Verify CSRF on the new host**

While logged in on `archive.czernin.eu`, perform a form POST (e.g. `/profile` → change display name → save). Expected: succeeds (no 403). This confirms `HOST_HEADER`/`PROTOCOL_HEADER` CSRF derivation.

- [ ] **Step 6: REGRESSION — verify icomb still works**

In another clean browser: full Google **and** Apple login on `https://archiver.icomb.place/`. Expected: both still work and land back on icomb authenticated.

**Rollback if icomb auth breaks:** re-add the two removed `OAUTH2_PROXY_REDIRECT_URL` lines and restore `ORIGIN: https://archiver.icomb.place` (frontend), commit, redeploy. Then debug the X-Forwarded-Host chain before retrying.

- [ ] **Step 7: Verify MCP self-links point at czernin**

Run: `curl -s "https://archive.czernin.eu/api/v1/search?q=test" | head -c 400` (or exercise an MCP tool) and confirm returned links use `https://archive.czernin.eu/api`.

- [ ] **Step 8: Finish the branch**

Open/merge the PR to `main` if not already done. Confirm CI is green.

---

## Notes for the implementer

- **Why no automated tests for most tasks:** this is infra/config (DNS, TLS, nginx, OAuth consoles, env vars) — not application logic. It cannot be exercised by Testcontainers. Verification is the explicit `curl`/browser checklist in each task. The only code touched (MCP self-link constant) is verified by `compileJava` + a grep; it changes a response string, not behavior worth a brittle unit test.
- **Cookie domains as a list:** oauth2-proxy accepts comma-separated values for `*_DOMAINS` env options; it selects the cookie domain matching the request host.
- **Header chain:** host nginx (`proxy-ws.conf`) sends `Host $host`; web nginx forwards `Host $host` + (new) `X-Forwarded-Host $host` to oauth2-proxy; with `REVERSE_PROXY=true` oauth2-proxy derives the callback from that host. SvelteKit reads `HOST_HEADER: host` (the same `Host` web nginx forwards) for CSRF.
