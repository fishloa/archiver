# Flyway squash — deferred, out-of-hours work

**Status:** agreed in principle 2026-09-08, deliberately deferred. Not urgent.

**Goal:** collapse V1–V24 into a single baseline migration representing the current schema,
so a fresh environment builds in one step and the schema is readable in one file.

## Why it is deferred rather than dropped

24 migrations, 112 KB, 21 tables, zero failed applications over 7 months. Flyway handles
hundreds of migrations routinely, so nothing is breaking. The gain is readability; the cost
is a delicate operation on the one database holding an irreplaceable archive. That trade is
fine with time and attention, and bad when squeezed between other work.

## Prerequisites

- A fresh verified `pg_dump` immediately before starting. Backups live in
  `/Volumes/External/Projects/archiver-backups/`.
- Docker available locally, or a scratch PostgreSQL 18 + pgvector to build into.
- No pipeline jobs running — check `select count(*) from job where status in
  ('pending','claimed')` is zero, so nothing writes mid-operation.

## Procedure

**1. Snapshot production's schema.**

```bash
PGPASSWORD=archiver pg_dump -h 192.168.19.130 -U archiver -d archiver \
  --schema-only --no-owner --no-privileges -f prod_schema.sql
```

As of 2026-09-08 this is 1,492 lines: 21 tables, 30 indexes, 44 constraints.

**2. Build the same schema from the existing migrations, on an empty database.**
Testcontainers already does this on every CI run, so the simplest route is a throwaway
container running V1–V24, then dump it the same way.

**3. Diff the two. This is the whole point of the exercise.**

Any difference means production and the migrations have drifted — something was changed by
hand outside Flyway at some point. **Resolve every difference before going further**, because
a squash freezes whichever version you baseline from. This step is worth doing on its own
merits even if the squash never happens: CI currently tests against a schema that is assumed,
not proven, to match production.

**4. Write `V1__baseline.sql` from the verified dump.** Strip `SET`/`SELECT pg_catalog`
preamble noise. Keep the `vector`/`pgvector` extension creation, and keep the `job_kind`
CHECK constraint including `ocr_page_paddle` — 4,969 historical job rows carry that value and
a narrower constraint fails validation against the existing table.

**5. Move V2–V24 out of the source tree.** They stay in git history; nothing is lost.

**6. Re-baseline production.** Flyway must be told it is already at the baseline rather than
allowed to run V1 against a populated database. Either `flyway baseline -baselineVersion=1`,
or replace the `flyway_schema_history` contents with a single row for V1 marked successful.
Verify with a read-only Flyway `info` before restarting the backend.

**7. Do the same for the test database** at 192.168.19.131, and any other environment.

**8. Verify** the backend starts, reports "Successfully validated 1 migration", and that a
Testcontainers run builds an identical schema.

## The failure mode to avoid

Flyway running the new `V1__baseline.sql` against the populated production database. Every
`CREATE TABLE` fails, or worse, a `DROP`/`CREATE` pair in the baseline destroys live data.
Step 6 exists solely to prevent this, and is the step to slow down on.

## Cheaper alternative if the squash keeps slipping

Steps 1–3 alone — commit `schema.sql` as a snapshot and diff it in CI. That delivers the
readable single-file schema and, more importantly, automatic drift detection, without
touching production at all.
