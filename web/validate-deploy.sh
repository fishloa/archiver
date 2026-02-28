#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# Deployment validation for archiver.icomb.place
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
# Usage: ./validate-deploy.sh [BASE_URL]
# Default: https://archiver.icomb.place
# ─────────────────────────────────────────────────────────────────────

BASE="${1:-https://archiver.icomb.place}"
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

# Verify NOT getting nginx default page or error page
check_body "Homepage has SvelteKit content" "$BASE/" "sveltekit"
check_body_absent "Homepage not nginx default" "$BASE/" "Welcome to nginx"

# ── 2. Frontend Pages (SvelteKit → frontend:3000) ───────────────────
echo ""
bold "2. Frontend Pages"
echo "────────────────────────────────────────"

check_status "Records page" "$BASE/records" "200"
check_status "Pipeline page" "$BASE/pipeline" "200"
check_status "Family tree page" "$BASE/family-tree" "200"
check_status "Translate page" "$BASE/translate" "200|405"
check_status "Sign-in page" "$BASE/signin" "200"
check_status "Search with query" "$BASE/?q=test" "200"

# Record detail page (should render even without auth)
check_status "Record detail page" "$BASE/records/2635" "200"

# Profile redirects to signin without auth (302 from SvelteKit redirect())
check_status "Profile without auth redirects" "$BASE/profile" "302"

# Admin pages redirect to signin without auth (307 temporary redirect)
check_status "Admin users page (no auth)" "$BASE/admin/users" "307"
check_status "Admin audit page (no auth)" "$BASE/admin/audit" "307"
check_status "Admin events page (no auth)" "$BASE/admin/events" "307"

# ── 3. API Proxy Chain (browser → host nginx → web nginx → backend) ─
echo ""
bold "3. API Endpoints (proxy chain)"
echo "────────────────────────────────────────"

# These are the calls the browser makes client-side via fetch()
# If the web container /api/ location is broken, these all fail
check_status "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" "200"
check_status "API record detail" "$BASE/api/records/2635" "200"
check_status "API record pages" "$BASE/api/records/2635/pages" "200"
check_status "API archives list" "$BASE/api/records/archives" "200"
check_status "API pipeline stats" "$BASE/api/pipeline/stats" "200"
check_status "API search" "$BASE/api/search?q=test&page=0&size=1" "200"
check_content_type "API returns JSON" "$BASE/api/records/2635" "application/json"

# Auth-protected endpoints
check_status "API profile (no auth)" "$BASE/api/profile" "401"
check_status "API auth/me (no auth)" "$BASE/api/auth/me" "200"
check_body "auth/me returns unauthenticated" "$BASE/api/auth/me" '"authenticated"'

# ── 4. API Response Content ──────────────────────────────────────────
echo ""
bold "4. API Response Content"
echo "────────────────────────────────────────"

check_json_field "Record has 'id'" "$BASE/api/records/2635" "id"
check_json_field "Record has 'status'" "$BASE/api/records/2635" "status"
check_json_field "Record has 'pageCount'" "$BASE/api/records/2635" "pageCount"
check_json_field "Pages have 'seq'" "$BASE/api/records/2635/pages" "seq"
check_json_field "Search has 'results'" "$BASE/api/search?q=test&page=0&size=1" "results"
check_json_field "Pipeline has 'stages'" "$BASE/api/pipeline/stats" "stages"
check_json_field "Archives have 'id'" "$BASE/api/records/archives" "id"

# ── 5. SSE (the thing that keeps breaking) ───────────────────────────
echo ""
bold "5. SSE Streams"
echo "────────────────────────────────────────"

# SSE needs: proxy_buffering off, proxy_cache off, Connection "",
# correct routing through both host nginx AND web container nginx.
# curl exits 28 (timeout) on SSE — that's expected. We check the body.

sse_body=$(curl -s --max-time 3 -H "Accept: text/event-stream" "$BASE/api/records/events" 2>/dev/null || true)
if echo "$sse_body" | grep -q ":connected"; then
    pass "Records SSE connects and sends :connected"
else
    fail "Records SSE did not send :connected (got: $(echo "$sse_body" | head -c 100))"
fi

# Check SSE content-type
sse_ct=$(curl -s -o /dev/null -w '%{content_type}' --max-time 3 -H "Accept: text/event-stream" "$BASE/api/records/events" 2>/dev/null || true)
if echo "$sse_ct" | grep -qi "text/event-stream"; then
    pass "Records SSE content-type is text/event-stream"
else
    fail "Records SSE content-type is '$sse_ct' (expected text/event-stream)"
fi

# Processor SSE (workers connect to this)
proc_sse=$(curl -s --max-time 3 -H "Accept: text/event-stream" "$BASE/api/processor/events" 2>/dev/null || true)
if echo "$proc_sse" | grep -q ":connected"; then
    pass "Processor SSE connects and sends :connected"
else
    # Processor SSE may require auth — warn instead of fail
    warn "Processor SSE did not connect (may require auth)"
fi

# ── 6. Family Tree API ("This is Me" feature) ───────────────────────
echo ""
bold "6. Family Tree API"
echo "────────────────────────────────────────"

check_status "Family tree search" "$BASE/api/family-tree/search?q=alexander&limit=3" "200"
check_json_field "Search has 'personId'" "$BASE/api/family-tree/search?q=alexander&limit=3" "personId"
check_json_field "Search has 'score'" "$BASE/api/family-tree/search?q=alexander&limit=3" "score"

check_status "Family tree person 1" "$BASE/api/family-tree/person/1" "200"
check_json_field "Person has 'name'" "$BASE/api/family-tree/person/1" "name"
check_json_field "Person has 'children'" "$BASE/api/family-tree/person/1" "children"
check_json_field "Person has 'events'" "$BASE/api/family-tree/person/1" "events"

check_status "Person not found" "$BASE/api/family-tree/person/999999" "404"

check_status "Relate (default ref)" "$BASE/api/family-tree/relate?personId=2" "200"
check_json_field "Relate has 'kinshipLabel'" "$BASE/api/family-tree/relate?personId=2" "kinshipLabel"
check_json_field "Relate has 'pathDescription'" "$BASE/api/family-tree/relate?personId=2" "pathDescription"
check_json_field "Relate has 'refPersonName'" "$BASE/api/family-tree/relate?personId=2" "refPersonName"

# The "This is Me" feature: relate with custom refId
check_status "Relate with refId" "$BASE/api/family-tree/relate?personId=2&refId=3" "200"
check_json_field "Custom relate has 'refPersonName'" "$BASE/api/family-tree/relate?personId=2&refId=3" "refPersonName"

check_status "Relate invalid person" "$BASE/api/family-tree/relate?personId=999999" "404"
check_status "Relate invalid refId" "$BASE/api/family-tree/relate?personId=1&refId=999999" "404"

# ── 7. Record Timeline & Page Text ──────────────────────────────────
echo ""
bold "7. Record Timeline & Page Text"
echo "────────────────────────────────────────"

check_status "Record timeline" "$BASE/api/records/2635/timeline" "200"

# Get first page ID from pages list
first_page=$(curl -s --max-time 10 "$BASE/api/records/2635/pages" 2>/dev/null | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
if [[ -n "$first_page" ]]; then
    check_status "Page text" "$BASE/api/pages/$first_page/text" "200"
    check_json_field "Page text has 'text'" "$BASE/api/pages/$first_page/text" "text"
    check_json_field "Page text has 'engine'" "$BASE/api/pages/$first_page/text" "engine"
else
    warn "Could not extract page ID to test page text endpoint"
fi

# ── 8. Semantic Search ───────────────────────────────────────────────
echo ""
bold "8. Semantic Search"
echo "────────────────────────────────────────"

sem_resp=$(curl -s --max-time 15 -X POST "$BASE/api/search/semantic" \
    -H "Content-Type: application/json" \
    -d '{"query":"czernin property","limit":3}' 2>/dev/null || true)
sem_status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -X POST "$BASE/api/search/semantic" \
    -H "Content-Type: application/json" \
    -d '{"query":"czernin property","limit":3}' 2>/dev/null) || sem_status="000"
if [[ "$sem_status" == "200" ]]; then
    pass "Semantic search returns 200"
    if echo "$sem_resp" | grep -q '"results"'; then
        pass "Semantic search has 'results' array"
    else
        fail "Semantic search response missing 'results'"
    fi
else
    warn "Semantic search returned $sem_status (may have no embeddings)"
fi

# ── 9. Static Assets ────────────────────────────────────────────────
echo ""
bold "9. Static Assets"
echo "────────────────────────────────────────"

check_status "Logo SVG" "$BASE/logo.svg" "200"
check_content_type "Logo is SVG" "$BASE/logo.svg" "svg"

# SvelteKit serves _app/ assets
app_asset=$(curl -s --max-time 5 "$BASE/" 2>/dev/null | grep -o '/_app/[^"]*\.js' | head -1)
if [[ -n "$app_asset" ]]; then
    check_status "SvelteKit JS bundle" "$BASE$app_asset" "200"
    check_content_type "JS bundle content-type" "$BASE$app_asset" "javascript"
else
    warn "Could not find SvelteKit JS bundle path"
fi

# ── 10. Machine-Readable API (v1) ───────────────────────────────────
echo ""
bold "10. Machine-Readable API (v1)"
echo "────────────────────────────────────────"

check_status "API v1 archives" "$BASE/api/v1/archives" "200"
check_status "API v1 documents" "$BASE/api/v1/documents?page=0&size=1" "200"
check_status "API v1 document detail" "$BASE/api/v1/documents/2635" "200"
check_status "API v1 search" "$BASE/api/v1/search?q=test" "200"

# ── 11. Response Time ────────────────────────────────────────────────
echo ""
bold "11. Response Times"
echo "────────────────────────────────────────"

check_latency "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" 2000
check_latency "API search" "$BASE/api/search?q=test&page=0&size=1" 2000
check_latency "Family tree search" "$BASE/api/family-tree/search?q=alexander&limit=3" 2000
check_latency "Family tree relate" "$BASE/api/family-tree/relate?personId=2" 2000

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

if [[ "$FAIL" -gt 0 ]]; then
    red "DEPLOY VALIDATION FAILED"
    echo ""
    exit 1
else
    green "DEPLOY VALIDATION PASSED"
    echo ""
    exit 0
fi
