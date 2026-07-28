#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# Deployment validation for archive.czernin.eu
#
# Tests the FULL proxy chain: browser → host nginx (443) →
# web container nginx (8099/8080) → backend (8080) / frontend (3000)
#
# Catches the issues we've actually hit:
#   - Host nginx routing to wrong port (502s)
#   - Web container missing /api/ location (search panel broken)
#   - SSE not connecting (proxy_buffering on, wrong route)
#   - Client-side API fetches failing (no /api/ proxy in web container)
#   - Family tree endpoints broken after refId changes
#   - Profile endpoints returning wrong status codes
#
# Auth lockdown (see web/nginx.conf):
#   - location /api/ auth_requests against oauth2-proxy; unauthenticated
#     callers get the synthetic @api_401 JSON body via
#     `error_page 401 = @api_401` -- a real 401, before the backend is
#     ever reached. This applies to every /api/* route below, including
#     /api/auth/me (backend permitAll(), but no dedicated nginx location
#     of its own, so it falls under this same gate through the public
#     path -- the frontend reaches it server-side via BACKEND_URL
#     instead, bypassing nginx), EXCEPT /api/mcp/ (no auth_request;
#     gated by the backend's McpTokenFilter/ROLE_MCP instead) and
#     /api/processor/jobs/events (no auth_request; gated by the backend's
#     ProcessorTokenFilter bearer token, for workers that have no
#     browser session). In practice workers reach the backend directly
#     via BACKEND_URL (http://backend:8080) and never traverse this
#     proxy at all, so the un-gated nginx location is defensive, not
#     load-bearing.
#   - location / (all browser pages) auth_requests too, but
#     `error_page 401 = /signin;` uses the `=` rewrite form, which
#     forces the response to 200 and serves web/signin.html. A bare
#     status check on a page route therefore proves nothing -- we assert
#     on the body (sign-in marker present, archive marker absent).
#
# Usage: ./validate-deploy.sh [BASE_URL]
# Default: https://archive.czernin.eu
# ─────────────────────────────────────────────────────────────────────

BASE="${1:-https://archive.czernin.eu}"
PASS=0
FAIL=0
WARN=0
ERRORS=""

green()  { printf "\033[32m%s\033[0m" "$1"; }
red()    { printf "\033[31m%s\033[0m" "$1"; }
yellow() { printf "\033[33m%s\033[0m" "$1"; }
bold()   { printf "\033[1m%s\033[0m" "$1"; }

pass() { printf "  $(green PASS)  %s\n" "$1"; PASS=$((PASS + 1)); }
fail() { printf "  $(red FAIL)  %s\n" "$1"; FAIL=$((FAIL + 1)); ERRORS="$ERRORS\n  - $1"; }
warn() { printf "  $(yellow WARN)  %s\n" "$1"; WARN=$((WARN + 1)); }

# ── Helpers ──────────────────────────────────────────────────────────

# Check HTTP status code (supports multiple expected codes separated by |)
check_status() {
    local desc="$1" url="$2" expect="$3"
    shift 3
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" "$url" 2>/dev/null) || code="000"
    # Support "200|302" syntax for multiple acceptable codes
    if echo "$expect" | grep -qw "$code"; then
        pass "$desc ($code)"
    else
        fail "$desc (got $code, expected $expect)"
    fi
}

# Check response body contains pattern
check_body() {
    local desc="$1" url="$2" pattern="$3"
    shift 3
    local body
    body=$(curl -s --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "$pattern"; then
        pass "$desc (body contains '$pattern')"
    else
        fail "$desc (body missing '$pattern')"
    fi
}

# Check response body does NOT contain pattern (for error detection)
check_body_absent() {
    local desc="$1" url="$2" pattern="$3"
    shift 3
    local body
    body=$(curl -s --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "$pattern"; then
        fail "$desc (body contains '$pattern')"
    else
        pass "$desc (no '$pattern')"
    fi
}

# Check JSON field exists and is non-null
check_json_field() {
    local desc="$1" url="$2" field="$3"
    shift 3
    local body
    body=$(curl -s --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "\"$field\""; then
        pass "$desc (JSON has '$field')"
    else
        fail "$desc (JSON missing '$field')"
    fi
}

# Check response time is under threshold (ms)
check_latency() {
    local desc="$1" url="$2" max_ms="$3"
    shift 3
    local time_ms
    time_ms=$(curl -s -o /dev/null -w '%{time_total}' --max-time 10 "$@" "$url" 2>/dev/null || echo "99")
    time_ms=$(echo "$time_ms" | awk '{printf "%d", $1 * 1000}')
    if [[ "$time_ms" -le "$max_ms" ]]; then
        pass "$desc (${time_ms}ms < ${max_ms}ms)"
    else
        warn "$desc (${time_ms}ms > ${max_ms}ms threshold)"
    fi
}

# Check content-type header
check_content_type() {
    local desc="$1" url="$2" expect="$3"
    shift 3
    local ct
    ct=$(curl -s -o /dev/null -w '%{content_type}' --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$ct" | grep -qi "$expect"; then
        pass "$desc (content-type: $ct)"
    else
        fail "$desc (content-type: '$ct', expected '$expect')"
    fi
}

# ─────────────────────────────────────────────────────────────────────
echo ""
bold "Validating deployment: $BASE"
echo "$(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "══════════════════════════════════════════════════════════════"

# ── 1. TLS & Basic Connectivity ─────────────────────────────────────
echo ""
bold "1. TLS & Connectivity"
echo "────────────────────────────────────────"

check_status "TLS handshake + homepage" "$BASE/" "200"
check_latency "Homepage latency" "$BASE/" 3000
check_content_type "Homepage is HTML" "$BASE/" "text/html"

# Unauthenticated, "/" is rewritten to the sign-in page (200), not the
# archive -- assert on the body, not just the status.
check_body "Homepage shows sign-in page (unauthenticated)" "$BASE/" 'id="googleBtn"'
check_body_absent "Homepage does not leak archive content (unauthenticated)" "$BASE/" "sveltekit"

# Verify NOT getting nginx default page or error page
check_body_absent "Homepage not nginx default" "$BASE/" "Welcome to nginx"

# ── 2. Frontend Pages (SvelteKit → frontend:3000) ───────────────────
echo ""
bold "2. Frontend Pages"
echo "────────────────────────────────────────"

# Every route below is served by location / in web/nginx.conf. Unauth-
# enticated, `error_page 401 = /signin;` rewrites ALL of them to 200 with
# web/signin.html -- including /profile and /admin/*, whose 302/307
# SvelteKit-level redirects (frontend/src/routes/profile/+page.server.ts,
# routes/admin/+layout.server.ts) never fire because the request never
# reaches the frontend. Assert the sign-in body, not just the status.
check_status "Records page" "$BASE/records" "200"
check_body "Records page shows sign-in page (unauthenticated)" "$BASE/records" 'id="googleBtn"'
check_body_absent "Records page does not leak archive content (unauthenticated)" "$BASE/records" "sveltekit"

check_status "Pipeline page" "$BASE/pipeline" "200"
check_body "Pipeline page shows sign-in page (unauthenticated)" "$BASE/pipeline" 'id="googleBtn"'
check_body_absent "Pipeline page does not leak archive content (unauthenticated)" "$BASE/pipeline" "sveltekit"

check_status "Family tree page" "$BASE/family-tree" "200"
check_body "Family tree page shows sign-in page (unauthenticated)" "$BASE/family-tree" 'id="googleBtn"'
check_body_absent "Family tree page does not leak archive content (unauthenticated)" "$BASE/family-tree" "sveltekit"

check_status "Translate page" "$BASE/translate" "200"
check_body "Translate page shows sign-in page (unauthenticated)" "$BASE/translate" 'id="googleBtn"'
check_body_absent "Translate page does not leak archive content (unauthenticated)" "$BASE/translate" "sveltekit"

check_status "Sign-in page" "$BASE/signin" "200"

check_status "Search with query" "$BASE/?q=test" "200"
check_body "Search page shows sign-in page (unauthenticated)" "$BASE/?q=test" 'id="googleBtn"'
check_body_absent "Search page does not leak archive content (unauthenticated)" "$BASE/?q=test" "sveltekit"

# Record detail page: unauthenticated it's the sign-in page too, not the record.
check_status "Record detail page" "$BASE/records/2635" "200"
check_body "Record detail page shows sign-in page (unauthenticated)" "$BASE/records/2635" 'id="googleBtn"'
check_body_absent "Record detail page does not leak archive content (unauthenticated)" "$BASE/records/2635" "sveltekit"

# Profile: no longer a SvelteKit-level 302 -- nginx rewrites to the sign-in page first.
check_status "Profile without auth" "$BASE/profile" "200"
check_body "Profile shows sign-in page (unauthenticated)" "$BASE/profile" 'id="googleBtn"'
check_body_absent "Profile does not leak archive content (unauthenticated)" "$BASE/profile" "sveltekit"

# Admin pages: no longer SvelteKit-level 307s -- same nginx rewrite applies.
check_status "Admin users page (no auth)" "$BASE/admin/users" "200"
check_body "Admin users page shows sign-in page (unauthenticated)" "$BASE/admin/users" 'id="googleBtn"'
check_body_absent "Admin users page does not leak archive content (unauthenticated)" "$BASE/admin/users" "sveltekit"
check_status "Admin audit page (no auth)" "$BASE/admin/audit" "200"
check_body "Admin audit page shows sign-in page (unauthenticated)" "$BASE/admin/audit" 'id="googleBtn"'
check_body_absent "Admin audit page does not leak archive content (unauthenticated)" "$BASE/admin/audit" "sveltekit"
check_status "Admin events page (no auth)" "$BASE/admin/events" "200"
check_body "Admin events page shows sign-in page (unauthenticated)" "$BASE/admin/events" 'id="googleBtn"'
check_body_absent "Admin events page does not leak archive content (unauthenticated)" "$BASE/admin/events" "sveltekit"

# ── 3. API Proxy Chain (browser → host nginx → web nginx → backend) ─
echo ""
bold "3. API Endpoints (proxy chain)"
echo "────────────────────────────────────────"

# These are the calls the browser makes client-side via fetch(). Unauth-
# enticated, location /api/'s auth_request fails and `error_page 401 =
# @api_401` returns a real 401 JSON body -- before the backend, and
# before any of these routes' own logic, is ever reached.
check_status "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" "401"
check_status "API record detail" "$BASE/api/records/2635" "401"
check_status "API record pages" "$BASE/api/records/2635/pages" "401"
check_status "API archives list" "$BASE/api/records/archives" "401"
check_status "API pipeline stats" "$BASE/api/pipeline/stats" "401"
check_status "API search" "$BASE/api/search?q=test&page=0&size=1" "401"
check_content_type "API 401 response is JSON" "$BASE/api/records/2635" "application/json"

# Auth-protected endpoints
check_status "API profile (no auth)" "$BASE/api/profile" "401"
# /api/auth/me is permitAll() at the backend (SecurityConfig), but it has
# no dedicated location in web/nginx.conf -- it falls under the generic
# location /api/'s auth_request gate like every other /api/ route, so
# unauthenticated it's the synthetic @api_401 401, not the backend's
# permitAll() response. This is fine: the UI's "signed out" vs "signed
# in but not allowlisted" check (fetchCurrentUser in
# frontend/src/lib/server/api.ts) only runs server-side from SvelteKit
# load functions (routes/+layout.server.ts, routes/admin/+layout.server.ts),
# which call BACKEND_URL (http://backend:8080) directly, bypassing nginx
# entirely. Nothing calls this endpoint from the browser through the
# public path, so the 401 here is correct, not a regression.
check_status "API auth/me (no auth)" "$BASE/api/auth/me" "401"

# Anonymous-access probes (no cookie, no headers) -- explicit regression
# coverage for the routes named in the auth-lockdown spec. /api/records
# and /api/records/events are already covered above / in section 5.
check_status "API admin users (no auth)" "$BASE/api/admin/users" "401"
# /api/mcp/ has no auth_request in web/nginx.conf (Claude.ai / MCP
# clients have no browser session) -- it's gated by the backend's
# McpTokenFilter requiring ROLE_MCP via bearer token, which Spring
# Security's default entry point answers with either 401 or 403
# (see McpTokenFilterTest.rejectsMcpRequestWithNoToken).
check_status "MCP endpoint (no auth)" "$BASE/api/mcp/sse" "401|403" -X POST

# Header-spoofing regression: X-Auth-Email must not be honoured through
# the proxy for a caller outside the trusted peer set (see
# AuthSpoofingRegressionTest.java / TrustedPeerResolver). Before the fix
# this returned 200 with the full user list.
#
# Scope note: through the public proxy this mainly re-verifies nginx's
# own 401 -- location /api/ requires a valid oauth2 session before
# proxy_pass and unconditionally overwrites X-Auth-Email with $auth_email
# regardless of what the client sent, so this probe can't actually reach
# the backend's TrustedPeerResolver guard the way a LAN-address request
# could. That guard is covered at the integration level by
# AuthSpoofingRegressionTest instead. This check still has value (it
# proves the header isn't honoured through the path real clients use),
# but it is not full coverage of the backend fix.
spoof_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -H 'X-Auth-Email: timothy.corbettclark@gmail.com' \
    "$BASE/api/admin/users" 2>/dev/null) || spoof_code="000"
if [[ "$spoof_code" == "000" || -z "$spoof_code" ]]; then
    fail "Spoofed X-Auth-Email probe could not reach the server (got '$spoof_code') -- result is inconclusive, not a pass"
elif [[ "$spoof_code" != "200" ]]; then
    pass "Spoofed X-Auth-Email rejected ($spoof_code)"
else
    fail "Spoofed X-Auth-Email accepted (200) -- privilege escalation regression"
fi

# ── 4. API Response Content ──────────────────────────────────────────
echo ""
bold "4. API Response Content"
echo "────────────────────────────────────────"

# Unauthenticated, every route above returns the shared @api_401 JSON
# body ({"error":"authentication required"}), not real content -- these
# checks now confirm the error shape rather than record/search/pipeline
# fields, since the underlying content is inaccessible anonymously.
check_json_field "Record 401 body has 'error'" "$BASE/api/records/2635" "error"
check_json_field "Pages 401 body has 'error'" "$BASE/api/records/2635/pages" "error"
check_json_field "Search 401 body has 'error'" "$BASE/api/search?q=test&page=0&size=1" "error"
check_json_field "Pipeline 401 body has 'error'" "$BASE/api/pipeline/stats" "error"
check_json_field "Archives 401 body has 'error'" "$BASE/api/records/archives" "error"

# ── 5. SSE (the thing that keeps breaking) ───────────────────────────
echo ""
bold "5. SSE Streams"
echo "────────────────────────────────────────"

# SSE needs: proxy_buffering off, proxy_cache off, Connection "",
# correct routing through both host nginx AND web container nginx.
# curl exits 28 (timeout) on SSE — that's expected. We check the body.

# /api/records/events gained auth_request (see location = /api/records/events
# in web/nginx.conf) -- unauthenticated it's now a real 401 from
# @api_401, so it never reaches the backend and never sends :connected.
check_status "Records SSE requires auth" "$BASE/api/records/events" "401" --max-time 3 -H "Accept: text/event-stream"
check_content_type "Records SSE (unauthenticated) returns JSON error" "$BASE/api/records/events" "application/json" --max-time 3 -H "Accept: text/event-stream"

# Processor SSE (workers connect to this): deliberately NOT auth_request-
# gated at nginx -- location = /api/processor/jobs/events (the real route,
# ProcessorController's @GetMapping("/jobs/events")) has no auth_request,
# because workers/scrapers have no browser session. Auth is enforced by
# the backend's ProcessorTokenFilter (bearer token) instead. Without a
# token it must not succeed, but the failure mode isn't necessarily nginx's
# 401 -- assert "not 200" rather than a specific code. Note: workers reach
# the backend directly via BACKEND_URL, not through nginx, so this
# un-gated location is defensive rather than load-bearing.
proc_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Accept: text/event-stream" "$BASE/api/processor/jobs/events" 2>/dev/null) || proc_code="000"
if [[ "$proc_code" == "000" || -z "$proc_code" ]]; then
    fail "Processor SSE probe could not reach the server (got '$proc_code') -- result is inconclusive, not a pass"
elif [[ "$proc_code" != "200" ]]; then
    pass "Processor SSE rejects unauthenticated caller ($proc_code)"
else
    fail "Processor SSE accepted unauthenticated caller (200)"
fi

# ── 6. Family Tree API ("This is Me" feature) ───────────────────────
echo ""
bold "6. Family Tree API"
echo "────────────────────────────────────────"

# /api/family-tree/** falls under location /api/ like everything else --
# unauthenticated it's gated before the family-tree logic (including its
# own 404 handling for unknown ids) ever runs, so every case below,
# valid or invalid input alike, now returns the same 401.
check_status "Family tree search" "$BASE/api/family-tree/search?q=alexander&limit=3" "401"
check_json_field "Family tree search 401 body has 'error'" "$BASE/api/family-tree/search?q=alexander&limit=3" "error"

check_status "Family tree person 1" "$BASE/api/family-tree/person/1" "401"
check_json_field "Family tree person 401 body has 'error'" "$BASE/api/family-tree/person/1" "error"

check_status "Person not found (still auth-gated first)" "$BASE/api/family-tree/person/999999" "401"

check_status "Relate (default ref)" "$BASE/api/family-tree/relate?personId=2" "401"
check_json_field "Relate 401 body has 'error'" "$BASE/api/family-tree/relate?personId=2" "error"

# The "This is Me" feature: relate with custom refId
check_status "Relate with refId" "$BASE/api/family-tree/relate?personId=2&refId=3" "401"
check_json_field "Custom relate 401 body has 'error'" "$BASE/api/family-tree/relate?personId=2&refId=3" "error"

check_status "Relate invalid person (still auth-gated first)" "$BASE/api/family-tree/relate?personId=999999" "401"
check_status "Relate invalid refId (still auth-gated first)" "$BASE/api/family-tree/relate?personId=1&refId=999999" "401"

# ── 7. Record Timeline & Page Text ──────────────────────────────────
echo ""
bold "7. Record Timeline & Page Text"
echo "────────────────────────────────────────"

check_status "Record timeline" "$BASE/api/records/2635/timeline" "401"

# Page id is no longer extractable anonymously -- /api/records/2635/pages
# now returns the @api_401 error body, not real page data. That's fine:
# the auth gate fires before route-specific backend logic runs, so any
# id proves the point without needing a real one.
check_status "Page text requires auth" "$BASE/api/pages/1/text" "401"

# ── 8. Semantic Search ───────────────────────────────────────────────
echo ""
bold "8. Semantic Search"
echo "────────────────────────────────────────"

# POST /api/search/semantic falls under location /api/ too -- unauthenticated
# it's gated the same as every other API route, before embeddings are ever
# queried.
check_status "Semantic search requires auth" "$BASE/api/search/semantic" "401" \
    -X POST -H "Content-Type: application/json" \
    -d '{"query":"czernin property","limit":3}'

# ── 9. Static Assets ────────────────────────────────────────────────
echo ""
bold "9. Static Assets"
echo "────────────────────────────────────────"

# /logo.svg is served through location / (SvelteKit static handling), so
# unauthenticated it's also rewritten to the sign-in page -- a plain 200
# check would pass for the wrong reason (see "Logo is SVG" content-type
# check this replaces, which would now be text/html, not svg). Converted
# to body assertions instead of dropped, so a regression that exempted
# static assets from the auth gate would still be caught.
check_status "Logo path (unauthenticated)" "$BASE/logo.svg" "200"
check_body "Logo path returns sign-in page, not the asset" "$BASE/logo.svg" 'id="googleBtn"'
# signin.html itself contains three inline <svg> elements (logo mark, Google
# and Apple button icons), so "<svg" is NOT a valid absence check here -- it
# would always fail even on a correctly-locked-down deploy. "sodipodi" is an
# Inkscape-only marker present throughout the real logo.svg export and never
# present in signin.html, so it discriminates the two bodies correctly.
check_body_absent "Logo path does not leak SVG markup" "$BASE/logo.svg" "sodipodi"

# SvelteKit serves _app/ assets -- bundle paths are no longer discoverable
# anonymously since "/" now returns signin.html (no /_app/ references), so
# this will always warn under lockdown. Left as a warn (not a fail): it's
# an honest "can't test this anonymously" rather than a false pass.
app_asset=$(curl -s --max-time 5 "$BASE/" 2>/dev/null | grep -o '/_app/[^"]*\.js' | head -1)
if [[ -n "$app_asset" ]]; then
    check_status "SvelteKit JS bundle" "$BASE$app_asset" "200"
    check_content_type "JS bundle content-type" "$BASE$app_asset" "javascript"
else
    warn "Could not find SvelteKit JS bundle path (expected while unauthenticated -- signin.html has no /_app/ references)"
fi

# ── 10. Machine-Readable API (v1) ───────────────────────────────────
echo ""
bold "10. Machine-Readable API (v1)"
echo "────────────────────────────────────────"

# /api/v1/** also falls under location /api/ -- same auth_request gate.
check_status "API v1 archives" "$BASE/api/v1/archives" "401"
check_status "API v1 documents" "$BASE/api/v1/documents?page=0&size=1" "401"
check_status "API v1 document detail" "$BASE/api/v1/documents/2635" "401"
check_status "API v1 search" "$BASE/api/v1/search?q=test" "401"

# ── 11. Response Time ────────────────────────────────────────────────
echo ""
bold "11. Response Times"
echo "────────────────────────────────────────"

check_latency "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" 2000
check_latency "API search" "$BASE/api/search?q=test&page=0&size=1" 2000
check_latency "Family tree search" "$BASE/api/family-tree/search?q=alexander&limit=3" 2000
check_latency "Family tree relate" "$BASE/api/family-tree/relate?personId=2" 2000

# ── 12. Authenticated Path (opt-in) ──────────────────────────────────
echo ""
bold "12. Authenticated Path (opt-in)"
echo "────────────────────────────────────────"

# Every check above this point is anonymous. The 2026-07-28 auth-lockdown
# review (finding C1) found a bug where SvelteKit's server-side reads
# never sent X-Auth-Email, so every content page 500'd for every
# signed-in user -- while all ~100 anonymous checks in this suite kept
# passing. An anonymous-only run structurally cannot catch that class of
# regression. There is no practical way to obtain a real oauth2-proxy
# session non-interactively, so this section is opt-in: point
# ARCHIVER_COOKIE_JAR at a cookie-jar file (Netscape format, as produced
# by `curl -c jar.txt` or exported from a browser after a real Google/
# Apple sign-in) containing a valid _oauth2_proxy session cookie.
AUTH_SKIPPED=0
if [[ -n "${ARCHIVER_COOKIE_JAR:-}" && -f "${ARCHIVER_COOKIE_JAR}" ]]; then
    echo "Using cookie jar: $ARCHIVER_COOKIE_JAR"
    check_status "Authenticated records list status" \
        "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" "200" \
        -b "$ARCHIVER_COOKIE_JAR"
    check_body "Authenticated records list returns content" \
        "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" '"content"' \
        -b "$ARCHIVER_COOKIE_JAR"
    check_status "Authenticated /records page loads" "$BASE/records" "200" \
        -b "$ARCHIVER_COOKIE_JAR"
    check_body_absent "Authenticated /records is not the sign-in page" \
        "$BASE/records" 'id="googleBtn"' -b "$ARCHIVER_COOKIE_JAR"
    check_status "Authenticated /api/auth/me status" "$BASE/api/auth/me" "200" \
        -b "$ARCHIVER_COOKIE_JAR"
    check_body "Authenticated /api/auth/me reports authenticated" \
        "$BASE/api/auth/me" '"authenticated":true' -b "$ARCHIVER_COOKIE_JAR"
else
    AUTH_SKIPPED=1
    WARN=$((WARN + 1))
    echo ""
    red "  ################################################################"
    echo ""
    red "  ##  SKIPPED: AUTHENTICATED CHECKS DID NOT RUN                ##"
    echo ""
    red "  ################################################################"
    echo ""
    echo "    Every check in this script, including the ones above, is"
    echo "    ANONYMOUS. This run CANNOT detect a regression that only"
    echo "    breaks the signed-in path (this is exactly how C1 -- every"
    echo "    content page 500ing for real users -- shipped past an"
    echo "    all-green anonymous suite in the 2026-07-28 review)."
    echo ""
    echo "    To enable: set ARCHIVER_COOKIE_JAR to a cookie-jar file"
    echo "    holding a valid oauth2-proxy session cookie, then re-run:"
    echo ""
    echo "        ARCHIVER_COOKIE_JAR=/path/to/cookies.txt ./validate-deploy.sh"
    echo ""
    red "  ################################################################"
    echo ""
fi

# ─────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
printf "Results: $(green "$PASS passed"), "
if [[ "$FAIL" -gt 0 ]]; then
    printf "$(red "$FAIL failed"), "
else
    printf "0 failed, "
fi
if [[ "$WARN" -gt 0 ]]; then
    printf "$(yellow "$WARN warnings")"
else
    printf "0 warnings"
fi
echo ""

if [[ "$FAIL" -gt 0 ]]; then
    echo ""
    red "FAILURES:"
    printf "$ERRORS\n"
    echo ""
fi

TOTAL=$((PASS + FAIL))
echo "Total: $TOTAL checks"
echo ""

if [[ "$AUTH_SKIPPED" -eq 1 ]]; then
    yellow "  NOTE: authenticated checks were SKIPPED -- this result only covers"
    echo ""
    yellow "  anonymous callers. Set ARCHIVER_COOKIE_JAR to also validate the"
    echo ""
    yellow "  signed-in path (see \"12. Authenticated Path\" section above)."
    echo ""
    echo ""
fi

if [[ "$FAIL" -gt 0 ]]; then
    red "DEPLOY VALIDATION FAILED"
    echo ""
    exit 1
else
    green "DEPLOY VALIDATION PASSED"
    echo ""
    exit 0
fi
