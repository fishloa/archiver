# Moving pages between records: move, split, concatenate

**Status:** design, approved in conversation 29 September 2026
**Author:** drafted with Claude Opus 5, for the archiver

## Why

Records are divided by a judgement made at ingest, and that judgement is sometimes
wrong. Signature 114-3-17 arrived as 391 pages and was split into eleven records
along the archive's own separator sheets; some of those boundaries will need
correcting once the text has been read. Today the only way to correct one is to
re-upload the pages into a different record and delete them from the old one,
which re-runs OCR, translation and embedding on every moved page — money spent to
fix a filing decision, and, because `page_ocr_history` keeps metadata only, a
re-OCR can replace a better transcription with a worse one and lose it.

## The constraint that shapes everything

**A page that has been through the pipeline must never go through it again.** The
scan and every model output move wholesale with the page. No OCR, no translation,
no embedding, no person matching is triggered by a move, a split or a concatenate.

The single exception is the record's stored searchable PDF, which is a local
render of text already held: it calls no model and costs nothing, and a PDF
containing pages the record no longer owns is the kind of wrongness that ends up
in a submission. It is rebuilt on both sides of every move.

## What the data model gives us for free, and what it does not

Everything keyed on `page_id` follows a page that keeps its id:

`page_text`, `page_translation`, `page_search`, `page_ocr_history`,
`page_person_match`, `entity_hit`, `evidence`.

Everything keyed on `record_id` has to be handled:

| Thing | Handling |
|---|---|
| `page.record_id` | updated — the move itself |
| `attachment.record_id` + `attachment.path` | re-parented, and **the file moves on disk** |
| `text_chunk.record_id` | re-parented, never deleted — deleting means re-embedding |
| `record.page_count`, `attachment_count` | recomputed on both |
| stored searchable PDF | rebuilt on both |
| `pipeline_event` | one written on each side, recording the move |

**The file must travel.** `IngestService.deleteRecord()` calls
`storageService.deleteRecordFiles(recordId)`, which recursively removes
`records/{id}/`. Emptying a record and then deleting it — the intended workflow —
would otherwise destroy the scans of pages that now live in another record, while
their rows survive pointing at nothing.

## API

Admin only. One primitive, two wrappers.

```
POST /api/admin/records/{id}/pages/move
     { "targetRecordId": 4029, "fromSeq": 66, "toSeq": 99, "atSeq": null }

POST /api/admin/records/{id}/split
     { "atSeq": 66, "title": "…", "description": "…" }

POST /api/admin/records/{id}/concat
     { "sourceRecordId": 4030 }
```

`atSeq` omitted appends to the end of the target. `split` creates a record and
moves `atSeq`…end into it. `concat` moves all of the source's pages onto the end
of this record. Each returns both records with their new page counts.

**Refusals, checked before anything is touched:**

- either record is on AI hold
- any job in `pending` or `claimed` touches a page being moved — a job in flight
  would write its text into the wrong record
- the range does not exist, is inverted, or overlaps itself
- source equals target
- the records are in different archives, unless `allowCrossArchive: true`

**Never automatic:** an emptied source record is left in place. Deleting it is a
separate, deliberate call to the existing delete endpoint.

## Ordering

The filesystem cannot join a database transaction, so the order is chosen so that
a failure leaves a duplicate rather than a hole.

1. validate; refuse early
2. **copy** each image to `records/{new}/attachments/pages/…`
3. one transaction: re-parent `page`, `attachment` (with its new path) and
   `text_chunk`; renumber `seq` on both records through the parking offset,
   because `(record_id, seq)` is a non-deferrable unique index; refresh counts;
   write a `pipeline_event` on each side
4. commit; then delete the original files
5. if the transaction fails, delete the copies; the originals are untouched

**Read-only sources.** A page whose image resolves into
`ARCHIVER_STORAGE_READONLY_ROOT` — which is how the test stack mounts the
production store, and how any deployment reading an archive it does not own
behaves — is copied into the writable root under the new record, and the original
is left alone. Step 4 skips it. This must not raise.

## Testing

**Automated, written first:**

- `page.id` preserved, and with it text, translation, search row, OCR history,
  person matches — compared either side by row id and content
- `text_chunk` rows re-parented, same ids, new `record_id`
- `seq` contiguous on both sides; a move into the middle of a target does not trip
  the unique index
- `page_count` and `attachment_count` correct on both
- **after a move the only job created is `build_searchable_pdf`** — the constraint
  above, asserted directly
- record status unchanged: `complete` stays `complete`
- image present at the new path, absent from the old
- **delete safety**: move pages out, delete the emptied record, assert the moved
  images still open
- every refusal above
- rollback: fail the transaction after the copies exist; copies removed, originals
  intact
- read-only source: copied, original left, no exception

**Non-production, before any tag.** Push to main untagged; Jenkins builds `:test`
and the test stack redeploys. Refresh its catalogue with
`deploy/seed-test-db.sh --records 50`, which dumps production and restores into
`archiver_test` on 5433, refusing a restore target it does not recognise. Then
exercise the real thing: split 4037, move a range into 4036, concatenate two
Prague records; confirm the text survived, the images open, both PDFs rebuilt, and
the job table holds no AI work. Only then tag for production.

## Deferred: record-agnostic storage

Storing images at `attachments/<sha256>` rather than under `records/{id}/` would
make a move a pure database transaction, remove the copy/commit/delete ordering
entirely, and retire the `p{seq}` collision class that already cost record 4006
seven page images. It needs either a re-lay of every stored file or a dual-read
layer, and it is its own project. The move logic here is written so that it would
not need to change: it asks `StorageService` where a file is and where it should
go, and if the answer stops depending on the record, the copy and delete steps
simply become no-ops.

## Out of scope

- retrying failed jobs (the thirty timed-out translations of 8 September need a
  separate endpoint; `reset-pipeline` deletes `page_text` and is not the fix)
- reordering pages within one record, which `insert` and `delete` already cover
- any change to how the pipeline itself runs
