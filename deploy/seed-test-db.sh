#!/usr/bin/env bash
#
# Refreshes the test database from production.
#
# The test stack reads the real scans (mounted read only) but needs its own copy of the catalogue
# to mutate. This dumps production and restores it into archiver_test on 5433.
#
# Production is opened read-only here: pg_dump takes no locks that block writes, and nothing in
# this script writes to the production database. The restore target is checked before anything is
# dropped, because the one mistake that matters is pointing this at production by accident.
#
#   ./seed-test-db.sh                  # full catalogue
#   ./seed-test-db.sh --records 50     # a sample, for a fast environment
#
set -euo pipefail

PROD_HOST="${PROD_HOST:-192.168.19.130}"
PROD_PORT="${PROD_PORT:-5432}"
PROD_DB="${PROD_DB:-archiver}"
PROD_USER="${PROD_USER:-archiver}"

TEST_HOST="${TEST_HOST:-192.168.19.130}"
TEST_PORT="${TEST_PORT:-5433}"
TEST_DB="${TEST_DB:-archiver_test}"
TEST_USER="${TEST_USER:-archiver}"

RECORDS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --records) RECORDS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# The guard that matters: never restore over production, whatever the variables say.
if [[ "$TEST_PORT" == "$PROD_PORT" && "$TEST_HOST" == "$PROD_HOST" && "$TEST_DB" == "$PROD_DB" ]]; then
  echo "Refusing: the restore target is the production database." >&2
  exit 1
fi
if [[ "$TEST_DB" != *test* ]]; then
  echo "Refusing: the target database name ($TEST_DB) does not contain 'test'." >&2
  exit 1
fi

DUMP="$(mktemp -t archiver-dump-XXXXXX).sql"
trap 'rm -f "$DUMP"' EXIT

echo "Dumping ${PROD_DB} from ${PROD_HOST}:${PROD_PORT}…"
PGPASSWORD="${PROD_PASSWORD:-archiver}" pg_dump \
  --host="$PROD_HOST" --port="$PROD_PORT" --username="$PROD_USER" \
  --no-owner --no-privileges --file="$DUMP" "$PROD_DB"
echo "  $(du -h "$DUMP" | cut -f1)"

echo "Restoring into ${TEST_DB} on ${TEST_HOST}:${TEST_PORT}…"
PGPASSWORD="${TEST_PASSWORD:-archiver}" psql \
  --host="$TEST_HOST" --port="$TEST_PORT" --username="$TEST_USER" --dbname=postgres \
  -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS ${TEST_DB} WITH (FORCE)" \
  -c "CREATE DATABASE ${TEST_DB}"
PGPASSWORD="${TEST_PASSWORD:-archiver}" psql \
  --host="$TEST_HOST" --port="$TEST_PORT" --username="$TEST_USER" --dbname="$TEST_DB" \
  -v ON_ERROR_STOP=1 --quiet --file="$DUMP"

if [[ -n "$RECORDS" ]]; then
  # Trim to a sample. The scans stay where they are — they are mounted read only and shared, so
  # trimming the catalogue costs nothing and gives a stack that starts in seconds.
  echo "Trimming to ${RECORDS} records…"
  PGPASSWORD="${TEST_PASSWORD:-archiver}" psql \
    --host="$TEST_HOST" --port="$TEST_PORT" --username="$TEST_USER" --dbname="$TEST_DB" \
    -v ON_ERROR_STOP=1 --quiet <<SQL
CREATE TEMP TABLE keep AS
  SELECT id FROM record WHERE EXISTS (SELECT 1 FROM page p WHERE p.record_id = record.id)
  ORDER BY id LIMIT ${RECORDS};
DELETE FROM text_chunk   WHERE record_id NOT IN (SELECT id FROM keep);
DELETE FROM job          WHERE record_id NOT IN (SELECT id FROM keep);
DELETE FROM record       WHERE id        NOT IN (SELECT id FROM keep);
SQL
fi

# The test stack must never be able to spend money or touch a provider's queue by inheriting
# production's settings. Turn every model off; an operator enables what a given test needs.
echo "Disabling AI providers in the test database…"
PGPASSWORD="${TEST_PASSWORD:-archiver}" psql \
  --host="$TEST_HOST" --port="$TEST_PORT" --username="$TEST_USER" --dbname="$TEST_DB" \
  -v ON_ERROR_STOP=1 --quiet \
  -c "UPDATE ai_implementation SET enabled = false" \
  -c "INSERT INTO pipeline_gate (kind, paused, reason, updated_at, updated_by)
      SELECT k, true, 'test environment: released deliberately', now(), 'seed-test-db'
      FROM unnest(ARRAY['translate_page','translate_page_upgrade','translate_record',
                        'embed_record','ocr_page_mistral']) k
      ON CONFLICT (kind) DO UPDATE SET paused = true, reason = EXCLUDED.reason"

PGPASSWORD="${TEST_PASSWORD:-archiver}" psql \
  --host="$TEST_HOST" --port="$TEST_PORT" --username="$TEST_USER" --dbname="$TEST_DB" \
  -t -A -c "SELECT 'records: ' || count(*) FROM record"

echo "Done. Test stack reads scans from the production store, read only."
