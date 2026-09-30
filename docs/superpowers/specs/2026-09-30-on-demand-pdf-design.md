# On-demand PDFs

**Status:** design, decided in conversation 30 September 2026
**Decided with the user:** every PDF is produced on demand and takes the same asynchronous
path; finished exports are kept 24 hours and reused for identical requests; the old synchronous
URLs are removed and every consumer moves to the new flow.
**Order of work:** this (A), then moving pages between records (C, see
`2026-09-29-page-move-split-concat-design.md`), then retiring the pipeline's PDF stage and the
stored PDFs (B, out of scope here).

## Why

Today there are two unrelated PDF mechanisms.

- **A stored whole-record PDF**, built by the pipeline (`build_searchable_pdf`, between
  `pdf_pending` and `pdf_done`), kept under `records/{id}/derivatives/pdf/searchable.pdf`, and
  served by `GET /api/records/{id}/pdf` through `record.pdf_attachment_id`. There are 3,262 of
  them, 7.3 GB, the largest 604 MB. Most are never downloaded.
- **A synchronous page-range export**, `GET /api/records/{id}/export-pdf`, which builds the PDF
  in memory as a `byte[]` and answers in the same request. English and side-by-side variants
  exist only here.

The stored PDF goes stale the moment a record's pages change, and nothing invalidates it:
`deletePage`, `insertPage` and `replacePage` leave a PDF that still contains a removed page.
Moving pages between records would add a fourth way to make it wrong. The synchronous export
cannot serve a large selection: it holds the whole document in memory and the request open.

One mechanism removes both problems. A PDF is requested, built by a worker, and downloaded when
it is ready. Nothing is stored that can go stale, and a 942-page record is no different from a
three-page extract.

## What a PDF export is

An export is **(record, variant, page selection)**.

- **variant**: `original` (the scans with a searchable text layer), `english` (the translation
  as text, one PDF page per source page), or `side-by-side` (scan and translation facing each
  other). These are the existing `PdfExportService.Variant` values.
- **page selection**: the existing range syntax (`1,3,5-10`), or nothing for the whole record.
  It is resolved to **page ids** when the request arrives. Positions shift when pages move;
  ids do not, so an export is about those pages whatever happens to their numbering afterwards.

The stored whole-record PDF is simply `original` over every page. It has no special status.

## Data

One table, created by migration `V18__pdf_export.sql`:

```sql
CREATE TABLE pdf_export (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id    bigint NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    variant      text   NOT NULL CHECK (variant IN ('original', 'english', 'side_by_side')),
    page_ids     bigint[] NOT NULL,
    fingerprint  text   NOT NULL,
    state        text   NOT NULL DEFAULT 'queued'
                 CHECK (state IN ('queued', 'building', 'ready', 'failed', 'expired')),
    path         text,
    bytes        bigint,
    error        text,
    requested_by text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz,
    expires_at   timestamptz
);
CREATE INDEX idx_pdf_export_open ON pdf_export (state) WHERE state IN ('queued', 'building');
CREATE INDEX idx_pdf_export_record ON pdf_export (record_id);
```

**Its own table, not the pipeline's `job` table.** Completing a pipeline job calls
`PipelineStateMachine.autoAdvance`, and an export has no business anywhere near that. The
`job` table also carries a `kind` CHECK and record-centric claiming that an export does not
need.

**Files** live at `exports/{xx}/{uuid}.pdf` under the storage root, the same shape as page
images: nothing about the record appears in the address. They are built to `…pdf.partial` and
moved into place atomically, so a reader never sees half a file.

**`fingerprint`** says what the pages *were* when the export was built: an `md5` over, for each
selected page in order, the page id, its attachment id, its `page_text` id and a hash of its
English text, and its `page_translation` ids. Re-OCR, a new translation, a replaced scan or a
moved page all change it. An export is reused only while the fingerprint of the same pages still
matches.

## API

All three are for signed-in users (`USER` or `ADMIN`); `SecurityConfig` already permits a `POST`
under `/api/**` for them and a `GET` for any allowlisted user.

### `POST /api/records/{recordId}/pdf-exports`

Body: `{"variant": "original" | "english" | "side-by-side", "pages": "1,3,5-10"}`. Both optional;
the defaults are `original` and the whole record.

| Situation | Answer |
|---|---|
| An identical export is `ready` and unexpired, and its fingerprint still matches | `200` with that export |
| An identical export is `queued` or `building` | `202` with that export (joined, not duplicated) |
| Otherwise | `202` with a new `queued` export |
| Unknown record | `404` |
| Unknown variant, malformed `pages`, or no page resolved | `400` |
| The requester already has three exports queued or building | `429` |

Response body, for all of the above:
`{"id": "…", "state": "queued", "pageCount": 12, "variant": "original", "bytes": null,
"expiresAt": null, "error": null}`.

### `GET /api/pdf-exports/{id}`

The same body, current. `state` is `queued`, `building`, `ready`, `failed` or `expired`; `bytes`
and `expiresAt` are set once `ready`; `error` once `failed`. `404` for an unknown id.

### `GET /api/pdf-exports/{id}/file`

`200` streams the PDF as an attachment, named for what it holds
(`record-{recordId}-original.pdf`, `-english.pdf`, `-original-and-english.pdf`). `409` while it is
`queued` or `building`, `410` once `expired`, `404` for an unknown id, and `409` with the error for
`failed`.

### Removed

`GET /api/records/{recordId}/pdf` and `GET /api/records/{recordId}/export-pdf`, and the
`FileController` methods behind them. Every consumer moves to the flow above in the same change
(below). `record.pdf_attachment_id` stays in the record response for now and is no longer read;
retiring it belongs to B.

## Execution

- **Claiming.** A scheduled worker takes the oldest `queued` row with
  `FOR UPDATE SKIP LOCKED`, sets it `building` and `started_at`, and builds it. Concurrency is
  `archiver.pdf-export.concurrency`, default **2**: a large export holds a temporary file of the
  size of the output, and two at once is enough to keep a person from waiting behind another.
- **Building.** Every variant is built to a file through a `PDDocument` backed by PDFBox's
  temp-file stream cache, never as a `byte[]`. `PdfExportService` gains
  `buildToFile(recordId, seqNumbers, variant, target)`; the English and side-by-side builders are
  split into `render…(doc, …)` methods the way `renderOriginal` already is, so all three share it.
  Finishing sets `ready`, `bytes`, `finished_at` and `expires_at = now() + 24 h`.
- **Failure.** `failed` with the first 500 characters of the message. There is no automatic retry;
  the person asks again, which makes a new export.
- **An interrupted build.** On every tick, a `building` row started more than two hours ago
  becomes `failed` with `interrupted`, and its `.partial` file is removed. A backend restart
  therefore cannot leave an export building for ever.
- **Expiry.** A scheduled reaper, every 15 minutes, deletes the file of every `ready` export
  past `expires_at` and marks it `expired`; rows expired more than seven days are deleted, so a
  stale link says `410` rather than `404` for a week.
- **Deleting a record** removes its export files as it removes its page images: `ON DELETE
  CASCADE` takes the rows, so `IngestService.deleteRecord` collects the paths first.
- **Free space.** A build refuses to start, failing with a clear message, when the storage root
  has less than 2 GB free.

**Temporary files.** PDFBox's stream cache writes to `java.io.tmpdir`, which in the backend
container is whatever `/tmp` is. The rehearsal on the 604 MB record measures it; if it is too
small the answer is to point `java.io.tmpdir` at a directory under the storage root, not to
build in memory.

## Consumers

**The viewer** gets one helper, `frontend/src/lib/pdf-export.ts`, with the logic as pure
functions so it can be tested without a browser: `exportRequest(variant, pages)` (the body),
`nextPollDelay(attempt)` (2 s, backing off to 5 s), and `describeState(state, error)` (the label
and whether it is terminal). The button calls `POST`, polls `GET …/{id}`, shows *Preparing PDF…*
while `queued` or `building`, and on `ready` navigates the page to `…/file` (an attachment, so
the page stays where it is). `failed` shows the error and offers a retry. It replaces the links
in `routes/records/[id]/+page.svelte` (the whole-record button and *Download N kept*) and
`routes/records/[id]/pages/[seq]/+page.svelte`. The button's condition becomes `pages.length > 0`;
`record.pdfAttachmentId` stops mattering. New strings in `en.ts`, `de.ts` and `cs.ts`.

**The machine API** (`ApiController`): `links.pdf` and `pdfUrl` are replaced by `pdfExportUrl`,
the `POST` endpoint, present whenever the record has pages, documented in the OpenAPI
description and in `.claude/skills/archiver-api/SKILL.md`. The MCP tool description that says
"links to images/PDF" is reworded to match.

## Testing

- **Integration** (Testcontainers, Java `HttpClient`, real pages with a generated JPEG):
  request → worker runs → `ready` → the file is a valid PDF with the expected page count, for
  each variant and for a page range; an identical request is reused, not rebuilt; an identical
  request while `building` joins it; changing a page's text, translation or scan changes the
  fingerprint and starts a new export; `409` before ready, `410` after expiry, `404` unknown;
  `400` for a bad variant, a malformed range, and a range naming no page; `429` at the fourth
  open export; a `building` row older than two hours becomes `failed` and loses its partial file;
  the reaper deletes an expired file and marks the row; deleting a record deletes its files;
  unauthenticated is refused.
- **Frontend**: `bun test` for the three pure functions; `bun run check` with no errors; and a
  real browser against the running test stack, clicking the button, because that is what caught
  the failure in v1.1.13.
- **Massive**: on the test stack, export record 3780 (the 604 MB one) as `original`, then a large
  range as `english`; record the time, peak memory of the backend container, and free space
  before and after.

## Out of scope

- Retiring the pipeline's PDF stage (`build_searchable_pdf`, `pdf_pending`, `pdf_done`) and
  deleting the 3,262 stored PDFs: deliverable **B**. Until then the pipeline keeps building PDFs
  nothing reads.
- Progress within a build. The state says queued or building; a page count would need the worker
  to report as it goes.
- Emailing or notifying when an export is ready.
