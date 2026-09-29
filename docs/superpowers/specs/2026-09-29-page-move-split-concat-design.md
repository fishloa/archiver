# Page identity, then moving pages between records

**Status:** design, agreed in conversation 29 September 2026
**Supersedes:** the first draft of this file, which built moves on the existing
storage layout and was rightly rejected as complicated. The complication was a
symptom; this is the cause.

## Why

Records are divided by a judgement made at ingest, and that judgement is sometimes
wrong. Signature 114-3-17 arrived as 391 pages, split into eleven records along the
archive's own separator sheets; some boundaries will need correcting once the text
is read. Today the only way to correct one is to re-upload pages into another
record and delete them from the old one, which re-runs OCR, translation and
embedding on every page — money spent to fix a filing decision, and, since
`page_ocr_history` keeps metadata only, a re-OCR can quietly replace a better
transcription with a worse one.

## The root cause

A page has a stable identity in the database — `page.id`, and `attachment.id` for
its image. Storage does not use either:

```
records/{recordId}/attachments/pages/p{seq}-{sha8}.jpg
```

A page's address on disk is a function of **the record it belongs to** and **its
position within that record** — the two things a move changes. Everything hard
follows from that:

- the file must be copied and deleted, so a move cannot be one transaction
- an elaborate copy → commit → delete ordering is needed to make failure survivable
- `deleteRecordFiles()` wipes `records/{id}/` wholesale, so a page whose row moved
  but whose file did not loses its scan
- and it is the same fault that made `p{seq}.jpg` collide and cost record 4006
  seven page images in September 2026

Give the file the identity the row already has and all of it disappears.

## Phase 1 — page images addressed by attachment id, then migrated

**New layout**, sharded so no directory holds a hundred thousand entries:

```
attachments/{id % 100:02d}/{id}.jpg      e.g. attachments/79/165779.jpg
```

**What moves to it:** page images, and any other attachment that belongs to a
*page* rather than to a record — the OCR figure crops written by
`ProcessorController` when Mistral finds a signature or a stamp.

**What deliberately stays under `records/{id}/`:** the stored searchable PDF and
the record-level PDF. These belong to the record, are regenerated for it, and never
move. Keeping them there keeps `records/{id}/` meaningful.

**Reading during and after migration.** `StorageService.resolveForRead()` already
falls back from the primary root to a read-only root; the same shape applies here.
A path is resolved at the new address first, then at the stored legacy path. No
flag day: new writes go to the new layout immediately, old files are read where
they are, and a backfill moves them.

**The backfill is a separate job, and it runs to completion before phase 2 is
written.** Not a step inside the storage change, and not something phase 2 waits on
halfway through: its own admin endpoint, its own release, its own run.

```
POST /api/admin/storage/migrate        { "limit": 500 }   → moves a batch, reports counts
GET  /api/admin/storage/migrate        → { legacy, migrated, missing }
```

It walks `attachment` rows whose `path` still matches the legacy pattern, copies
each file to its new address, updates `path`, deletes the original — one row at a
time, resumable, no lock held across a copy, safe to stop and restart. Roughly
100,000 files; it can run for days without anyone noticing. A row whose file is
already missing is counted and skipped, never treated as a failure and never
retried forever.

**The gate.** Phase 2 does not begin until `GET` reports `legacy: 0`. At that point
every page image is at an address that does not mention its record, the legacy
read-fallback has nothing left to find, and a move really is only a database
statement. Starting the moves against a half-migrated store would put the old
copy/commit/delete ordering straight back into the design — which is the thing this
whole rewrite exists to remove.

**`deleteRecord()` must change, and this is the part that bites if missed.** It
currently deletes the `records/{id}/` tree, which will no longer contain the page
images. It must delete the files of that record's `attachment` rows by address, and
then the record tree for what remains. Without this, deleting a record leaks every
page image it owned, for ever.

**Tests for phase 1**

- a new page image is written at `attachments/{shard}/{id}.jpg` and its
  `attachment.path` records that
- a file at a legacy path is still found by `resolveForRead` after the change
- the backfill moves a legacy file, updates the row, removes the original, and is
  idempotent when run twice
- the backfill is resumable: two calls with `limit` smaller than the backlog
  together migrate everything, and neither moves a row twice
- a row whose file has already vanished is counted as `missing`, leaves the row
  alone, and does not stop the batch
- the status endpoint reports `legacy: 0` only when no row matches the legacy
  pattern — this number is the gate on phase 2, so it must not lie
- `deleteRecord` removes attachment-addressed files as well as the record tree,
  proved by asserting the files are gone
- two pages in one record whose images are byte-identical get distinct addresses —
  the `p{seq}` collision class cannot recur, because ids are unique

## Phase 2 — move, split, concatenate

**Begins only once the backfill reports `legacy: 0`.** With that done a move is a
database statement. No file operations, no
ordering argument, no rollback logic, no read-only-root special case.

```
POST /api/admin/records/{id}/pages/{pageId}/move   { "targetRecordId": 4029 }
POST /api/admin/records/{id}/split                 { "splitAtSeq": 66, "title": "…" }
POST /api/admin/records/{id}/concat                { "sourceRecordId": 4030 }
```

Admin only. The page is addressed by **`pageId`**, not by `seq`: ids are stable,
seq is not, and a caller iterating over seq numbers moves the wrong pages as the
earlier ones shift.

**What each call does, in one transaction:**

- `UPDATE page SET record_id = ?` for the pages concerned
- renumber `seq` contiguously on both records, shifting through the parking offset
  because `(record_id, seq)` is a non-deferrable unique index
- re-parent `text_chunk` rows — **never delete them**, deleting means re-embedding
- recompute `page_count` and `attachment_count` on both
- re-parent the page's `attachment` row to the new record; its address does not
  change, because the address no longer mentions the record
- write a `pipeline_event` on each side recording the move

`split` creates a record and moves `splitAtSeq`…end into it. The new record
inherits `archiveId`, `lang`, `metadataLang`, `ocrEngine`, `translationQuality` and
`referenceCode` — a split is one document recognised as two, not a new acquisition
— and takes `title` and `description` from the request. `concat` moves all of the
source's pages onto the end of this record; the surviving metadata is this record's.

**The constraint that governs all of it:** a page that has been through the
pipeline never goes through it again. No OCR, no translation, no embedding, no
person matching. Everything keyed on `page_id` — `page_text`, `page_translation`,
`page_search`, `page_ocr_history`, `page_person_match`, `entity_hit`, `evidence` —
follows the page untouched because the page keeps its id.

The one exception is the stored searchable PDF, a local render of text already
held: it calls no model, and a PDF containing pages the record no longer owns is
the kind of wrongness that reaches a submission. Rebuilt on both sides — but
**removed rather than rebuilt** on a record left with no pages.

**Refusals, checked before anything is touched:** either record on AI hold; a job
`pending` or `claimed` against a page being moved, which would write its text into
the record the page has just left; unknown page or record; the page does not belong
to the record in the path; source equals target; different archives unless
`allowCrossArchive: true`.

**Never automatic:** an emptied source record is left in place. Deleting it is a
separate, deliberate call.

**Tests for phase 2**

- `page.id` preserved, and with it text, translation, search row, OCR history and
  person matches — compared either side by row id and content
- `text_chunk` re-parented, same chunk ids, new `record_id`
- `seq` contiguous on both sides; the unique index never trips
- `page_count` and `attachment_count` correct on both
- **the only job created is `build_searchable_pdf`** — the constraint, asserted
- record status unchanged: `complete` stays `complete`
- the image is readable from the target record afterwards, at the same address
- **delete safety**: move a page out, delete the emptied record, assert the moved
  page's image still opens
- every refusal above
- `split` inheritance, and `concat` leaving the source empty but present

## Proving it off production

Both phases go to the test stack before any tag. Push to main untagged; Jenkins
builds `:test`; the test stack redeploys itself. Refresh its catalogue with
`deploy/seed-test-db.sh --records 50`, which dumps production and restores into
`archiver_test` on 5433 and refuses a restore target it does not recognise.

The test stack mounts the production store **read-only** at
`ARCHIVER_STORAGE_READONLY_ROOT` and writes to its own. That makes it the right
place to prove the backfill's behaviour when a source file cannot be deleted: it is
copied to the new address, the original is left alone, and nothing raises.

Then exercise the real thing on seeded Prague records: split 4037, move pages into
4036, concatenate two records; confirm the text survived, the images open, the PDFs
rebuilt, and the job table holds no AI work. Only then tag.

## Out of scope

- content-addressing by sha256, which would dedupe identical scans but makes one
  file shared by several records and needs reference counting before anything can
  be deleted
- retrying failed jobs — the thirty timed-out translations of 8 September need
  their own endpoint; `reset-pipeline` deletes `page_text` and is not the fix
- reordering pages within one record, which `insert` and `delete` already cover
- any change to how the pipeline itself runs
