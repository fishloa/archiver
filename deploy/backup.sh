#!/usr/bin/env bash
# Backs up the archive: the production database and the image store, as one snapshot.
#
# Run ON the host that runs the stack (zelkova), as a user in the docker group:
#
#   ssh zelkova 'bash -s' < deploy/backup.sh
#   ssh zelkova 'KEEP=8 bash -s' < deploy/backup.sh
#
# A snapshot is a directory named for when it began:
#
#   <BACKUP_ROOT>/2026-09-30T230000/
#       db/archiver.dump      pg_dump -Fc of the whole database (one consistent transaction)
#       db/archiver.dump.sha256
#       db/toc.txt            the dump's table of contents, proof it is readable
#       store/                the image store, as files
#       MANIFEST.txt          what was taken, when, from which release, how big
#   <BACKUP_ROOT>/latest      a symlink to the newest complete snapshot
#
# The database is dumped FIRST and the files second. Files are only ever added, so the files
# taken afterwards are a superset of what the dump refers to: every attachment row in the dump has
# its file in the snapshot. restore.sh --verify proves exactly that.
#
# Files are copied with rsync --link-dest against the previous snapshot, so the second and later
# snapshots copy only what is new and cost almost nothing in space; every snapshot still looks like
# a complete copy. Deleting an old snapshot never harms a newer one.
#
# Left out, because they are rebuilt: exports/ (PDFs kept 24 hours) and derivatives/thumbnails/.
#
# The snapshot is written to a .partial-* directory and renamed only when complete, so `latest`
# never points at half a backup. The data is personal: everything is created owner-only (umask 077).
#
# Environment (all optional):
#   BACKUP_ROOT   where snapshots go                  /mnt/ash-backups/archiver
#   STORE         the image store to copy             /mnt/ash-public/archiver
#   PG_CONTAINER  the Postgres container              postgres_18
#   PG_USER       database user for pg_dump           postgres
#   PG_DB         the database                        archiver
#   BACKEND_URL   for the release number in the manifest (best effort)  http://10.0.9.3:8080
#   KEEP          keep only this many snapshots, deleting the oldest. Empty (default) never deletes.

set -euo pipefail
umask 077

BACKUP_ROOT="${BACKUP_ROOT:-/mnt/ash-backups/archiver}"
STORE="${STORE:-/mnt/ash-public/archiver}"
PG_CONTAINER="${PG_CONTAINER:-postgres_18}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-archiver}"
BACKEND_URL="${BACKEND_URL:-http://10.0.9.3:8080}"
KEEP="${KEEP:-}"

stamp="$(date +%Y-%m-%dT%H%M%S)"
work="$BACKUP_ROOT/.partial-$stamp"
final="$BACKUP_ROOT/$stamp"
started="$(date -Is)"

log() { printf '%s  %s\n' "$(date +%T)" "$*"; }
die() { log "FAILED: $*"; exit 1; }

heartbeat_pid=""
on_exit() {
  status=$?
  [ -n "$heartbeat_pid" ] && kill "$heartbeat_pid" 2>/dev/null || true
  if [ "$status" -ne 0 ]; then
    log "FAILED (exit $status). The incomplete snapshot is left for inspection at $work"
    log "It is never used by restore.sh or by the next run; delete it when you are done with it."
  fi
}
trap on_exit EXIT

# ---- preflight -----------------------------------------------------------------------------
command -v rsync >/dev/null || die "rsync is not installed"
docker exec "$PG_CONTAINER" true 2>/dev/null || die "cannot reach container $PG_CONTAINER (is this the stack's host, and are you in the docker group?)"
[ -d "$STORE" ] || die "image store $STORE does not exist"
mkdir -p "$BACKUP_ROOT"
[ -w "$BACKUP_ROOT" ] || die "$BACKUP_ROOT is not writable"

previous=""
if [ -L "$BACKUP_ROOT/latest" ] && [ -d "$BACKUP_ROOT/latest/store" ]; then
  previous="$(readlink -f "$BACKUP_ROOT/latest")"
fi

store_bytes="$(du -sb --exclude=exports --exclude=thumbnails "$STORE" | cut -f1)"
avail_bytes="$(df --output=avail -B1 "$BACKUP_ROOT" | tail -1 | tr -d ' ')"
if [ -z "$previous" ]; then
  need=$((store_bytes + 10 * 1024 * 1024 * 1024))
else
  need=$((20 * 1024 * 1024 * 1024))
fi
[ "$avail_bytes" -ge "$need" ] || die "not enough space on $BACKUP_ROOT: $((avail_bytes / 1048576)) MiB free, need about $((need / 1048576)) MiB"

log "snapshot $stamp -> $final"
log "store $STORE is $((store_bytes / 1073741824)) GiB; $((avail_bytes / 1073741824)) GiB free at the destination; previous snapshot: ${previous:-none (this is a full copy)}"
mkdir -p "$work/db" "$work/store"

# ---- 1. the database -----------------------------------------------------------------------
log "dumping database $PG_DB (custom format, one transaction)"
docker exec -i "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$work/db/$PG_DB.dump"
[ -s "$work/db/$PG_DB.dump" ] || die "the dump is empty"

log "checking the dump can be read back"
docker exec -i "$PG_CONTAINER" pg_restore --list < "$work/db/$PG_DB.dump" > "$work/db/toc.txt" \
  || die "pg_restore could not read the dump it just wrote"
entries="$(grep -vc '^;' "$work/db/toc.txt" || true)"
[ "$entries" -gt 50 ] || die "the dump's table of contents has only $entries entries; something is wrong"
( cd "$work/db" && sha256sum "$PG_DB.dump" > "$PG_DB.dump.sha256" )
dump_bytes="$(stat -c %s "$work/db/$PG_DB.dump")"
log "dump: $((dump_bytes / 1048576)) MiB, $entries objects, sha256 $(cut -c1-16 "$work/db/$PG_DB.dump.sha256")…"

# ---- 2. the image store --------------------------------------------------------------------
link_dest=()
if [ -n "$previous" ]; then
  link_dest=(--link-dest="$previous/store")
fi

log "copying the image store (progress every 2 minutes)"
( while sleep 120; do log "  …$(du -sb "$work/store" 2>/dev/null | cut -f1 | awk '{printf "%.1f GiB", $1/1073741824}') copied so far"; done ) &
heartbeat_pid=$!

# Idle I/O priority and lowest CPU priority: this reads the same disks the live archive serves from.
polite=()
command -v ionice >/dev/null && polite+=(ionice -c3)
command -v nice >/dev/null && polite+=(nice -n 19)

"${polite[@]}" rsync -a --numeric-ids --info=stats2,name0 \
  --exclude='/exports/' --exclude='/derivatives/thumbnails/' \
  "${link_dest[@]}" "$STORE/" "$work/store/"

kill "$heartbeat_pid" 2>/dev/null || true
heartbeat_pid=""

files="$(find "$work/store" -type f | wc -l)"
new_bytes="$(du -sb "$work/store" | cut -f1)"
log "store: $files files"

# ---- 3. manifest and commit ----------------------------------------------------------------
version="$(curl -s --max-time 5 "$BACKEND_URL/api/version" 2>/dev/null || echo 'unavailable')"
{
  echo "snapshot:      $stamp"
  echo "started:       $started"
  echo "finished:      $(date -Is)"
  echo "host:          $(hostname)"
  echo "release:       $version"
  echo "database:      $PG_DB via $PG_CONTAINER (pg_dump -Fc)"
  echo "dump bytes:    $dump_bytes"
  echo "dump objects:  $entries"
  echo "dump sha256:   $(cut -d' ' -f1 "$work/db/$PG_DB.dump.sha256")"
  echo "store source:  $STORE"
  echo "store files:   $files"
  echo "store bytes:   $new_bytes (apparent size of the snapshot; hard-linked against ${previous:-nothing})"
  echo "excluded:      exports/ derivatives/thumbnails/"
} > "$work/MANIFEST.txt"

mv "$work" "$final"
ln -sfn "$stamp" "$BACKUP_ROOT/latest"
log "DONE: $final"
log "verify it restores: bash deploy/restore.sh $final --verify   (run on this host)"

# ---- 4. optional pruning -------------------------------------------------------------------
if [ -n "$KEEP" ]; then
  mapfile -t all < <(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20??-??-??T??????' | sort)
  excess=$(( ${#all[@]} - KEEP ))
  if [ "$excess" -gt 0 ]; then
    for old in "${all[@]:0:excess}"; do
      log "pruning old snapshot $old (keeping the newest $KEEP)"
      rm -rf -- "$old"
    done
  fi
fi
