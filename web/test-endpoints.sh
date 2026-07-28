#!/usr/bin/env bash
# Smoke test for archiver endpoints through the full proxy chain.
# Usage: ./test-endpoints.sh [BASE_URL]
# Default: https://archive.czernin.eu
#
# All requests here are anonymous (no cookie, no auth headers) unless
# explicitly noted. Under the auth lockdown:
#   - API routes (location /api/ in web/nginx.conf) fail auth_request and
#     get the synthetic @api_401 JSON response -> 401.
#   - Browser page routes (location /) fail auth_request too, but
#     `error_page 401 = /signin;` REWRITES the status to 200 and serves
#     web/signin.html. A bare status check on those routes would pass
#     even during a total lockdown failure, so we assert on the body.

BASE="${1:-https://archive.czernin.eu}"
PASS=0
FAIL=0

check() {
    local desc="$1" url="$2" expect="$3"
    shift 3
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" "$url" 2>/dev/null) || code="000"
    # Supports "401|403" style alternation, same as validate-deploy.sh's check_status.
    if echo "$expect" | grep -qw "$code"; then
        printf "  \033[32mPASS\033[0m  %s (%s)\n" "$desc" "$code"
        PASS=$((PASS + 1))
    else
        printf "  \033[31mFAIL\033[0m  %s (got %s, expected %s)\n" "$desc" "$code" "$expect"
        FAIL=$((FAIL + 1))
    fi
}

check_body() {
    local desc="$1" url="$2" pattern="$3"
    shift 3
    local body
    body=$(curl -s --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "$pattern"; then
        printf "  \033[32mPASS\033[0m  %s (body contains '%s')\n" "$desc" "$pattern"
        PASS=$((PASS + 1))
    else
        printf "  \033[31mFAIL\033[0m  %s (body missing '%s')\n" "$desc" "$pattern"
        FAIL=$((FAIL + 1))
    fi
}

check_body_absent() {
    local desc="$1" url="$2" pattern="$3"
    shift 3
    local body
    body=$(curl -s --max-time 10 "$@" "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "$pattern"; then
        printf "  \033[31mFAIL\033[0m  %s (body contains '%s')\n" "$desc" "$pattern"
        FAIL=$((FAIL + 1))
    else
        printf "  \033[32mPASS\033[0m  %s (body does not contain '%s')\n" "$desc" "$pattern"
        PASS=$((PASS + 1))
    fi
}

echo "Testing $BASE"
echo "──────────────────────────────────────"

# Frontend pages (unauthenticated -> 200, but body is the sign-in page,
# not the archive. Status alone proves nothing here -- see header note.)
check "Homepage" "$BASE/" "200"
check_body "Homepage shows sign-in page (unauthenticated)" "$BASE/" 'id="googleBtn"'
check_body_absent "Homepage does not leak archive content (unauthenticated)" "$BASE/" "sveltekit"

check "Records page" "$BASE/records" "200"
check_body "Records page shows sign-in page (unauthenticated)" "$BASE/records" 'id="googleBtn"'
check_body_absent "Records page does not leak archive content (unauthenticated)" "$BASE/records" "sveltekit"

check "Pipeline page" "$BASE/pipeline" "200"
check_body "Pipeline page shows sign-in page (unauthenticated)" "$BASE/pipeline" 'id="googleBtn"'
check_body_absent "Pipeline page does not leak archive content (unauthenticated)" "$BASE/pipeline" "sveltekit"

check "Search page" "$BASE/?q=test" "200"
check_body "Search page shows sign-in page (unauthenticated)" "$BASE/?q=test" 'id="googleBtn"'
check_body_absent "Search page does not leak archive content (unauthenticated)" "$BASE/?q=test" "sveltekit"

# API endpoints via proxy chain -- unauthenticated -> real 401 from
# location /api/'s `error_page 401 = @api_401` (JSON body, no rewrite).
check "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" "401"
check "API record detail" "$BASE/api/records/2635" "401"
check "API record pages" "$BASE/api/records/2635/pages" "401"
check "API pipeline stats" "$BASE/api/pipeline/stats" "401"
check "API search" "$BASE/api/search?q=test&page=0&size=1" "401"
check "API archives" "$BASE/api/records/archives" "401"

# The 401 responses are the shared @api_401 JSON body, not record content.
check_body "API record 401 body has 'error' field" "$BASE/api/records/2635" '"error"'
check_body "API pages 401 body has 'error' field" "$BASE/api/records/2635/pages" '"error"'

# SSE endpoint: gained auth_request (see task 8) -- now a real 401, no
# `:connected` body, since the request never reaches the backend.
check "SSE requires auth" "$BASE/api/records/events" "401" --max-time 3 -H "Accept: text/event-stream"

# Auth-protected endpoints return 401 without auth
check "Profile without auth" "$BASE/api/profile" "401"

# /api/auth/me is permitAll() at the backend (SecurityConfig), but it has
# no dedicated nginx location -- it falls under the generic location
# /api/'s auth_request gate like everything else, so unauthenticated it's
# the synthetic @api_401 401, same as any other /api/ route. This is
# fine: the UI's "signed out" vs "signed in but not allowlisted" check
# (fetchCurrentUser in frontend/src/lib/server/api.ts) only runs
# server-side from SvelteKit load functions, which call BACKEND_URL
# (http://backend:8080) directly, bypassing nginx entirely. Nothing
# calls this endpoint from the browser through the public path.
check "Auth/me without auth" "$BASE/api/auth/me" "401"

# Sign-in page itself, unchanged.
check "Sign-in page" "$BASE/signin" "200"

# Static assets: /logo.svg is served through location / (SvelteKit static
# handling), so unauthenticated it now returns the sign-in page too, not
# the SVG. A plain 200 check would pass for the wrong reason.
check "Logo path (unauthenticated)" "$BASE/logo.svg" "200"
check_body "Logo path returns sign-in page, not the asset" "$BASE/logo.svg" 'id="googleBtn"'
# signin.html itself contains three inline <svg> elements (logo mark, Google
# and Apple button icons), so "<svg" is NOT a valid absence check here -- it
# would always fail even on a correctly-locked-down deploy. "sodipodi" is an
# Inkscape-only marker present throughout the real logo.svg export and never
# present in signin.html, so it discriminates the two bodies correctly.
check_body_absent "Logo path does not leak SVG markup" "$BASE/logo.svg" "sodipodi"

# Anonymous-access probes (no cookie, no headers) -- explicit regression
# coverage for the routes named in the auth-lockdown spec. /api/records
# and /api/records/events are already covered above.
check "API admin users requires auth" "$BASE/api/admin/users" "401"
check "API v1 archives requires auth" "$BASE/api/v1/archives" "401"
# /api/mcp/ is NOT auth_request-gated at nginx (workers/Claude.ai need to
# reach it without a browser session) -- it's gated by the backend's
# McpTokenFilter requiring ROLE_MCP via bearer token, which returns
# either 401 or 403 depending on Spring Security's default entry point.
check "MCP endpoint requires auth token" "$BASE/api/mcp/sse" "401|403" -X POST

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
    printf "  \033[31mFAIL\033[0m  %s (could not reach server, got '%s' -- inconclusive, not a pass)\n" "Spoofed X-Auth-Email rejected" "$spoof_code"
    FAIL=$((FAIL + 1))
elif [[ "$spoof_code" != "200" ]]; then
    printf "  \033[32mPASS\033[0m  %s (%s)\n" "Spoofed X-Auth-Email rejected" "$spoof_code"
    PASS=$((PASS + 1))
else
    printf "  \033[31mFAIL\033[0m  %s (got 200 -- privilege escalation regression)\n" "Spoofed X-Auth-Email rejected"
    FAIL=$((FAIL + 1))
fi

echo "──────────────────────────────────────"
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
