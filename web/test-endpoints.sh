#!/usr/bin/env bash
# Smoke test for archiver endpoints through the full proxy chain.
# Usage: ./test-endpoints.sh [BASE_URL]
# Default: https://archiver.icomb.place

BASE="${1:-https://archiver.icomb.place}"
PASS=0
FAIL=0

check() {
    local desc="$1" url="$2" expect="$3"
    shift 3
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" "$url" 2>/dev/null) || code="000"
    if [[ "$code" == "$expect" ]]; then
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

echo "Testing $BASE"
echo "──────────────────────────────────────"

# Frontend pages (200)
check "Homepage" "$BASE/" "200"
check "Records page" "$BASE/records" "200"
check "Pipeline page" "$BASE/pipeline" "200"
check "Search page" "$BASE/?q=test" "200"

# API endpoints via proxy chain (200)
check "API records list" "$BASE/api/records?page=0&size=1&sortBy=id&sortDir=desc" "200"
check "API record detail" "$BASE/api/records/2635" "200"
check "API record pages" "$BASE/api/records/2635/pages" "200"
check "API pipeline stats" "$BASE/api/pipeline/stats" "200"
check "API search" "$BASE/api/search?q=test&page=0&size=1" "200"
check "API archives" "$BASE/api/records/archives" "200"

# API record returns JSON with expected fields
check_body "Record JSON has id" "$BASE/api/records/2635" '"id"'
check_body "Pages JSON is array" "$BASE/api/records/2635/pages" '"seq"'

# SSE endpoint (curl exits 28 on timeout — expected for long-lived stream)
check_body "SSE connects" "$BASE/api/records/events" ":connected" --max-time 3 -H "Accept: text/event-stream"

# Auth-protected endpoints return 401 without auth
check "Profile without auth" "$BASE/api/profile" "401"

# Static assets
check "Favicon/logo" "$BASE/logo.svg" "200"

echo "──────────────────────────────────────"
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
