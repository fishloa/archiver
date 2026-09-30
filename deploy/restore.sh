#!/usr/bin/env bash
# Restores, or proves restorable, a snapshot taken by backup.sh.
#
# Run ON the host that runs the stack (zelkova), as a user in the docker group.
#
#   restore.sh SNAPSHOT --verify
#       The default, and safe: restores the dump into a SCRATCH database, counts what came back,
#       checks that every file the database refers to is in the snapshot, then drops the scratch
#       database. Touches nothing live. Writes VERIFIED.txt into the snapshot.
#
#   restore.sh SNAPSHOT --db NAME
#       Restores the dump into a NEW database called NAME. Refuses if NAME already exists, and
#       refuses the live database name.
#
#   restore.sh SNAPSHOT --store DIR
#       Copies the snapshot's files into DIR. Refuses if DIR is not empty or is the live store.
#
#   restore.sh SNAPSHOT --db NAME --store DIR     both
#
# It has no mode that overwrites the live database or the live store. Replacing production is a
# deliberate, manual act: restore to a new name and directory with this script, check them, stop the
# stack, and swap them in yourself.
#
# Environment (all optional): PG_CONTAINER (postgres_18), PG_USER (postgres), PG_DB (archiver, the
# LIVE database name, which --db refuses), STORE (/mnt/ash-public/archiver, the live store, which
# --store refuses).

set -euo pipefail
umask 077

PG_CONTAINER="${PG_CONTAINER:-postgres_18}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-archiver}"
STORE="${STORE:-/mnt/ash-public/archiver}"

log() { printf '%s  %s\n' "$(date +%T)" "$*"; }
die() { log "FAILED: $*"; exit 1; }
usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

[ $# -ge 2 ] || usage
snapshot="$(readlink -f "$1")"; shift
mode=""; db_target=""; store_target=""
while [ $# -gt 0 ]; do
  case "$1" in
    --verify) mode="verify" ;;
    --db) db_target="${2:?--db needs a name}"; shift ;;
    --store) store_target="${2:?--store needs a directory}"; shift ;;
    *) usage ;;
  esac
  shift
done
[ -n "$mode" ] || [ -n "$db_target" ] || [ -n "$store_target" ] || usage

dump="$snapshot/db/$PG_DB.dump"
[ -f "$dump" ] || die "$snapshot has no db/$PG_DB.dump (is that a snapshot directory?)"
[ -d "$snapshot/store" ] || die "$snapshot has no store/"
docker exec "$PG_CONTAINER" true 2>/dev/null || die "cannot reach container $PG_CONTAINER"

psql_in() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -v ON_ERROR_STOP=1 "$@"; }
db_exists() { [ "$(psql_in -d postgres -At -c "select 1 from pg_database where datname='$1'")" = "1" ]; }

check_dump_intact() {
  log "checking the dump against its checksum"
  ( cd "$snapshot/db" && sha256sum -c "$PG_DB.dump.sha256" >/dev/null ) || die "the dump does not match its recorded checksum: the backup is damaged"
}

restore_db_into() {
  local name="$1"
  [ "$name" != "$PG_DB" ] || die "refusing to restore over the live database '$PG_DB'"
  db_exists "$name" && die "database '$name' already exists; choose a new name"
  log "creating database $name and restoring the dump into it (this can take a while)"
  psql_in -d postgres -c "create database \"$name\"" >/dev/null
  # --no-owner/--no-privileges: a restore into a scratch name should not depend on the live roles.
  docker exec -i "$PG_CONTAINER" pg_restore -U "$PG_USER" -d "$name" --no-owner --no-privileges --exit-on-error < "$dump" \
    || { log "pg_restore failed"; return 1; }
}

restore_store_into() {
  local dir="$1"
  [ "$(readlink -f "$dir")" != "$(readlink -f "$STORE")" ] || die "refusing to restore over the live store $STORE"
  if [ -e "$dir" ] && [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then die "$dir is not empty"; fi
  mkdir -p "$dir"
  log "copying the snapshot's files into $dir"
  rsync -a --numeric-ids --info=stats2,name0 "$snapshot/store/" "$dir/"
}

# ---- verify ---------------------------------------------------------------------------------
if [ "$mode" = "verify" ]; then
  scratch="archiver_verify_$(date +%Y%m%d%H%M%S)"
  cleanup() { db_exists "$scratch" && psql_in -d postgres -c "drop database \"$scratch\"" >/dev/null && log "dropped scratch database $scratch"; }
  trap cleanup EXIT

  check_dump_intact
  restore_db_into "$scratch" || die "the dump did not restore cleanly"

  log "counting what came back"
  report="$snapshot/VERIFIED.txt"
  {
    echo "verified:  $(date -Is) on $(hostname)"
    echo "snapshot:  $(basename "$snapshot")"
    for t in record page attachment page_text page_translation text_chunk; do
      printf '%-18s %s\n' "$t" "$(psql_in -d "$scratch" -At -c "select count(*) from $t")"
    done
  } | tee "$report"

  log "checking every file the database refers to is in the snapshot"
  paths="$(mktemp)"; files="$(mktemp)"; missing="$(mktemp)"
  trap 'rm -f "$paths" "$files" "$missing"; cleanup' EXIT
  psql_in -d "$scratch" -At -c "select path from attachment order by path" | sort -u > "$paths"
  ( cd "$snapshot/store" && find . -type f | sed 's|^\./||' | sort -u ) > "$files"
  comm -23 "$paths" "$files" > "$missing"
  n_paths="$(wc -l < "$paths")"; n_files="$(wc -l < "$files")"; n_missing="$(wc -l < "$missing")"
  {
    echo "attachment rows:           $n_paths"
    echo "files in the snapshot:     $n_files"
    echo "rows with no file:         $n_missing"
  } | tee -a "$report"

  if [ "$n_missing" -gt 0 ]; then
    echo "first missing paths:" | tee -a "$report"
    head -20 "$missing" | tee -a "$report"
    echo "RESULT: FAIL ($n_missing attachment rows have no file in the snapshot)" | tee -a "$report"
    exit 1
  fi
  echo "RESULT: PASS (the dump restores, and every attachment row has its file)" | tee -a "$report"
  exit 0
fi

# ---- restore to a new name / directory -------------------------------------------------------
check_dump_intact
if [ -n "$db_target" ]; then
  restore_db_into "$db_target" || die "restore into $db_target failed; the half-restored database is left for you to inspect or drop"
  log "restored into database $db_target"
fi
if [ -n "$store_target" ]; then
  restore_store_into "$store_target"
  log "restored files into $store_target"
fi
log "DONE. Nothing live was touched."
