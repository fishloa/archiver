# Changelog

Releases are tags. A tagged build moves `:latest` and deploys production; an
untagged build on main moves `:test` and deploys the test stack.

**A release must be tagged on its own commit.** Jenkins tracks what it has built
by commit SHA, so a tag placed on a commit that has already been built is not a
new revision, no build is triggered, and the release silently never happens —
which is exactly what v1.0.0 did on its first attempt. Add the entry below,
commit, then tag that commit.

## v1.1.17 — 1 October 2026

**Pages can be moved between records, a record split in two, and two joined.**
`POST /api/admin/records/{id}/pages/{pageId}/move`, `…/{id}/split` and
`…/{id}/concat` (admin only). A page keeps its id, so its text, translations,
search row, OCR history, person matches and embedding chunks follow it untouched:
nothing is re-read, re-translated, re-embedded or re-matched, and no job of any
kind is created. A move is one database transaction; the page images do not move,
because since v1.1.15 their addresses no longer mention a record. An emptied
source record is left in place for you to delete.

**What it refuses, and why.** Both records must be `complete` and off AI hold,
with no job pending or running against either (its result would land in the wrong
record), no PDF export of either being prepared, every page image at an
`attachments/…` address, and page numbers running 1..n. Different archives need
`allowCrossArchive`. A refusal changes nothing. A move touches no PDF: PDFs are
built on demand, so one requested afterwards already reflects the new pages.

**A split's English title is supplied, not generated.** The new record inherits
archive, languages, OCR engine, translation quality and reference code.

**Found in review, fixed before release.** The pipeline audit queued an embedding
for any complete record without an `embed_record` job, which a split-made record
would have been, re-embedding it within a minute; it now skips a record that
already has chunks. Page replace, delete and insert now take the record lock, so
they cannot interleave with a move.

**Sign-in.** The sign-in page could be cached by the browser under any URL that
had been answered while signed out, including SvelteKit's data requests, which
made every menu link silently do nothing. It is now `no-store`, and signing in
returns to the page that was asked for rather than always the home page. Browsers
that already hold a cached copy need their site data for archive.czernin.eu
cleared once.

Migration V19 adds `pages_moved` to the allowed `pipeline_event` values.

## v1.1.16 — 1 October 2026

**Every PDF is now built on request, by a worker, and downloaded when it is
ready.** There was a stored whole-record PDF that went stale the moment a
record's pages changed, and a synchronous export that assembled the whole
document on the heap before answering. Both are replaced by one path whatever
the size: ask (`POST /api/records/{id}/pdf-exports`), poll
(`GET /api/pdf-exports/{id}`), download (`GET /api/pdf-exports/{id}/file`). It
covers all three variants (original scans, English, side by side) and any page
selection. A 942-page record builds a 362 MB PDF in about two minutes with the
backend's memory unchanged; a 150-page record of full-resolution scans builds a
604 MB one in under three.

**How it behaves.** A finished PDF is kept 24 hours, and an identical request
reuses it. "Identical" is decided by a fingerprint of what the pages were — their
scans, text, translations, and the catalogue and archive fields the cover sheet
prints — so a re-OCR, a new translation, a replaced scan, a moved page or a
corrected date starts a new export instead of serving an old one. Builds run in a
scratch area under the storage root, never `/tmp`, and are renamed into
`exports/{xx}/{uuid}.pdf` when complete. A restart fails whatever was building and
empties the scratch area; a reaper expires old exports and deletes their files. A
page selection is bounded by the record's own pages, so `1-99999999` means "all of
them" and cannot exhaust memory.

**The viewer.** The whole-record button, *Download N kept* and the page viewer's
kept-pages button share one component: it asks, shows *Preparing PDF…*, and
downloads when ready, and stops waiting if you leave the page or after thirty
minutes (asking again rejoins the running export).

**Safety.** The only things this feature ever deletes are its own `exports/…`
files and `pdf_export` rows, and every deletion checks the path is under
`exports/`. It never touches a page image, a `page_*` row or a stored PDF.
Migration V18 adds one table and changes nothing existing.

**Removed.** `GET /api/records/{id}/pdf` and `/export-pdf`. In the machine API
`links.pdf` and `pdfUrl` become `links.pdfExport` and `pdfExportUrl`, present
whenever a record has pages. The stored searchable PDFs are no longer served;
they are still built by the pipeline and are to be retired separately.

**Known.** Anyone still holding an old `/pdf` or `/export-pdf` link now gets a
`500` rather than a `404`: the global exception handler turns every unmapped route
into a 500, which is older than this release and deserves its own change.

## v1.1.15 — 29 September 2026

**A page image's file no longer depends on which record it is in.** It was stored
at `records/{recordId}/attachments/pages/p{seq}-{sha8}.jpg` — a function of the
record and of the page's position, the two things moving a page changes. That is
why moving pages between records was hard, and the same fault cost record 4006
seven images in September when two pages computed one name. New page images are
written to `attachments/{xx}/{uuid}.jpg`, a name that says nothing about the page.
Nothing on the read side changes: every reader follows `attachment.path` as
stored, so old and new rows coexist.

**Deleting had to change with it.** Page images no longer sit under
`records/{id}/`, so removing that directory would have leaked every scan a record
owned. `deleteRecord`, `deletePage`, `replacePage` and `replaceAllPages` now delete
the files of what they remove, after the database commit. The last three
previously left the file behind.

**The existing images move in their own job.** `POST /api/admin/storage/migrate`
(`{"limit": N}`) and `GET` for `{legacy, migrated, missing}`. A file is given its
new name by hard link — instant, nothing rewritten, the row never pointing at a
file that is not there — and copied and byte-compared instead where it cannot be
linked. The old name goes only once no row names it. A row whose file is gone is
marked (`missing_since`, V17) and counted, not retried. Nothing in this release
moves pages between records; that waits until `legacy` reaches zero.

## v1.1.14 — 28 September 2026

**Fixes the edit form v1.1.13 broke.** Opening a Transkribus row did nothing
visible: the language-map field seeded its editing state from inside the
template, Svelte 5 refused the state change during render, and the form stopped
rendering at that point — a form with no table and no model pickers. The template
now only reads; edits are written from event handlers, and the row logic sits in
`settings-form.ts` with its own tests. Type-checking could not have caught it, so
this one was verified by clicking it in a browser against the live backend.

## v1.1.13 — 28 September 2026

**Transkribus can be configured from the admin page at last.** `ProviderApi` had
no `transkribus` entry, and the form renders whatever the backend describes — so
the row showed no fields, and the only way to choose a model was to edit the
settings JSON by hand. It now declares its real settings: default model, model by
language, the typescript equivalents, credits per month, tick, poll and timeout,
language model, token URL, client id and username variable.

**And pressing Save on that page would have destroyed the configuration.**
`settingsFrom` built the settings object from the posted fields alone, while the
API replaces settings wholesale — so every key the form had not rendered was
dropped on save, `htrByLang` included. It now merges over the row's stored
settings. The fix applies to every provider, and has the tests.

**Language and model are edited as a pair.** One row per language: a code and a
model chosen from the live catalogue, shown with its error rate and training size.
A model that does not list the language says so in place — Text Titan II reads
twelve languages, Czech is not one of them, and discovering that through a job
costs a credit and yields confident nonsense.

**The catalogue endpoint returned nothing for `de`.** The archive speaks ISO 639-1,
Transkribus publishes ISO 639-2, and the two were compared directly; both are now
accepted.

## v1.1.12 — 28 September 2026

**The Transkribus engine is data again, all the way down.** The worker captured
its `ai_implementation` row at boot, so changing a model, an endpoint or a
credential took a deployment — and enabling Transkribus in a process that had
started without a row did nothing whatever, because no worker had been registered
to notice. `TranskribusConfig` now reads its row through `AiRegistry` on use, with
a ten-second window so one page's accessor calls are one query. A missing row is
an answer rather than a crash, so a row can be added, disabled or deleted while
the archive runs, and the worker asks on every tick whether there is an engine,
logging only when the answer changes. The tick period alone is still fixed at
registration, because that is what a scheduled task is.

## v1.1.11 — 23 September 2026

**`transkribus/upload` answers 404 for an unknown record, not 500.** The hold
check read the record row with `queryForObject`, which throws when there is no
row, so a wrong id surfaced as an internal server error that told the caller
nothing. Found by probing the endpoint against production immediately after the
v1.1.10 deploy, which is the reason to probe.

## v1.1.10 — 23 September 2026

**A record says which OCR engine reads it.** The engine was one deployment-wide
setting, so a file of handwriting was read by the print engine and then had to be
found and re-OCR'd a page at a time. `ocrEngine` on the ingest request — beside
the `translationQuality` hint that was already there — records the caller's
knowledge of what the document is, and both the state machine and the audit pass
honour it. `NULL` keeps the deployment default, which is every existing record.

An engine nothing claims is refused at the API with the list of kinds that do
exist, rather than queueing jobs no worker will ever take. The check counts
*enabled* registry rows rather than credentialled ones: an ingest may run long
before the pages are read, and a key absent this minute is a deployment matter.
`reocr-page`, which starts work immediately, still checks the credential too.

**`POST /api/admin/transkribus/upload`.** Uploading pages to Transkribus was the
one step of that round trip with no endpoint, so it was done with ad-hoc curl and
the file name the import matches pages on — `rec<recordId>_seq<seq>.jpg` — was
retyped by hand each time. The endpoint uploads a page or a whole record and
returns the docIds. Recognition still cannot be started from the API on this
plan, so the Run is pressed in the web app and `import-transkribus` collects it.

## v1.1.9 — 19 September 2026

**Page images are named by digest, not by sequence alone.** The sequence is not
unique: inserting a page renumbers the ones after it, so two live pages could
compute the same file name and the second write destroyed the first. Splitting
seven sheets of one record left seven pages showing their neighbour's image.

Images now store as `p{seq}-{sha8}.jpg`. Existing paths are untouched.

439 tests, 0 failures.

## v1.1.8 — 19 September 2026

**Pages can be inserted and deleted.** Until now a page could be replaced by
another single image, or every page wiped for a re-ingest; neither turns one
scanned sheet holding two documents into two pages.

```
DELETE /api/admin/records/{id}/pages/{seq}          close the gap behind it
POST   /api/admin/records/{id}/pages/{seq}/insert   shift the rest up
```

Admin only — both destroy or renumber. With the existing replace they are enough
to correct a scan in place: the left half replaces the page, the right half is
inserted behind it. No image handling in the backend; rotating and cutting happen
outside it.

`(record_id, seq)` is a non-deferrable unique index, so renumbering shifts through
a high offset and back rather than in place.

436 tests, 0 failures.

## v1.1.7 — 19 September 2026

**A record's catalogue entry can be corrected through the API.** Title and
description head every exported PDF, so editorial written into them travels with
the document to whoever it is sent.

```
POST /api/admin/records/{id}/catalogue?title=&description=&referenceCode=
```

Only the fields given change. `title_en` and `description_en` are cleared rather
than left describing the old text, and `translate_record` is queued to write them
again.

429 tests, 0 failures.

## v1.1.6 — 19 September 2026

**A record can be frozen.** `record.ai_held_at` is a condition in all three claim
queries, so no worker — single or batch — will claim a held record's jobs. Held
like a paused stage rather than a cancelled one: the work waits and runs when the
hold lifts. Translation, embedding and matching are charged per call, and this is
what stops a broken record spending money while it is being put right.

```
POST /api/admin/records/{id}/ai-hold?reason=&cancelQueued=
POST /api/admin/records/{id}/ai-hold?hold=false
```

**`cancel-jobs` drops its `recordId` filter.** A record's queued work is stopped
by holding the record, which also stops more being queued; cancelling without
holding only invites the state machine to enqueue it again.

**New: `GET /api/admin/jobs`.** An id-only cancel is useless without a way to find
ids, and the alternative was a database session.

**MCP gains four tools:** `list_jobs`, `hold_record`, `cancel_job`, `reocr_page`.
The three writing tools require `ROLE_ADMIN` and **fail closed** — no provable
admin authority, no action — so an ordinary MCP user cannot reach them even if
the security context never arrives at the tool. `reocr_page` refuses a record on
hold rather than queueing work that would run the moment the hold lifts.

`.claude/skills/archiver-api` documents the whole interface, MCP first.

425 tests, 0 failures.

## v1.1.5 — 19 September 2026

**`POST /api/admin/cancel-jobs` addresses a job or a record, never a pattern.**
The `kind` and `withinMinutes` filter shipped in v1.1.4 cancels whatever happens
to match at the moment it runs, including work queued by something else that is
going along fine. It now takes exactly one of `jobId` or `recordId`, refuses
neither-or-both, and returns the jobs it stopped. Pending and claimed only; a
finished job is left as it was.

408 tests, 0 failures.

## v1.1.4 — 19 September 2026

**The Transkribus import can no longer lose work.** A collection holds every
document ever uploaded, so importing one whole puts each document back over
whatever the archive holds now.

- `POST /api/admin/import-transkribus` needs a `docId`, or an explicit
  `all=true`, before it will sweep a collection.
- A **shorter** transcript from a **different** engine is refused unless
  `overwrite=true`. `page_ocr_history` keeps the engine name and a character
  count, never the text, so a transcription replaced by a worse one is gone.
- A transcript identical to the stored text is skipped rather than rewritten.
  Advancing an unchanged page re-translates it, rebuilds the record's PDF and
  re-embeds the record — charged for, and producing what was already there.

**`reocr-page` no longer deletes `page_text` at enqueue time.** Both writers
delete immediately before inserting their own row, so deleting early bought
nothing and cost the page its text whenever the job then failed.

**New: `POST /api/admin/cancel-jobs`.** Pending and claimed jobs only, never a
finished one, and it refuses to run without a `kind` or a `recordId`.

**V14 carries the Transkribus collection id as data.** The setting defaulted to
0, documented as "the account's first collection"; TrpServer answers that with
*"Bad or no collection ID"* and every recognition job fails.

404 tests, 0 failures.

## v1.1.3 — 18 September 2026

- **A page re-read on demand is carried onward whichever engine reads it.**
  `POST /api/admin/reocr-page` puts `andThen` in the job payload, and only the
  Transkribus worker honoured it. A page sent back to Mistral was therefore
  re-transcribed and then left holding the translation of the text it had just
  replaced: record 4006 page 28 displayed an English "Unable to translate…" over
  3,406 characters of perfectly legible German, because that English had been
  made from the transcription the re-read discarded. The batch OCR stage now
  carries such a page through translation, the record's PDF and its embedding,
  while an ordinary first-pass page — which has no `andThen` — still leaves the
  record to the state machine.

## v1.1.2 — 18 September 2026

- **A record's PDF and embedding are queued once, not once per page.**
  `advanceSinglePage` enqueued `build_searchable_pdf` and `embed_record` for
  every page it advanced, so importing 27 re-transcribed pages queued 27 of each
  against the same three records. Both jobs cover the whole record, and embedding
  is charged per record: nine extra embeddings and three extra PDF builds had
  already run before the duplicates were deleted. A record-level job is now
  skipped when one of that kind is already pending — but not when one is merely
  claimed, since that worker may have read the record before this page was
  written.

## v1.1.1 — 18 September 2026

- **A re-transcribed page can now reach the rest of the pipeline.**
  `advanceSinglePage` logged a `pipeline_event` of "page_retranscribed", which
  reads perfectly well and which the CHECK constraint on that column rejects.
  The insert threw, aborting the import that called it: of 27 pages transcribed
  in Transkribus at a credit each, the first was written and the rest were left
  untouched. The event now uses values the schema accepts and the page identity
  goes in the free-text detail column, where it belongs.

  Covered by four tests against a real database, because the constraint lives in
  the schema and no unit test could have caught this — which is exactly how it
  reached production.

## v1.1.0 — 17 September 2026

Handwriting. The archive's OCR could not read it and did not say so.

- **Transkribus registered as a second OCR engine, for handwritten pages.**
  Mistral OCR reads typescript well and cannot read German handwriting at all,
  but it does not fail when it tries: it returns fluent, plausible German that is
  substantially wrong. Record 3505 carried the surname "Czernin" as Germin,
  Gernin, Perrin and Chernin across eleven pages with nothing in the output to
  flag any of them, and record 4006 had "beschuldigt" — accused — rendered as
  "schuldig", guilty, in a document about a treason charge. For an archive
  backing a citizenship submission, a wrong transcription that reads well is more
  dangerous than none.

  Which model runs is a database row, not code: a per-language map sends German,
  Czech and English to the model trained for each. The credit allowance is small
  and billed per page, so the worker counts what it has already spent and stops
  before overshooting, and checks before claiming rather than failing a job it
  cannot pay for.

  Two things measured against the live account changed the design. **API access
  is not part of the Scholar plan** — the Metagrapho API answers 401 and the
  classic API refuses the TrHtr super models even with the plan attached — so the
  super models are started by a person in the web app and collected by an import
  path, while Czech, whose models are PyLaia, needs no human at all. And **Text
  Titan II does not do Czech**, so Czech is pinned to its own model.

  Verified on record 3505 page 110 against a transcription read by eye: Text
  Titan II returned "31. XII. 1943 verlängert wurde" and "Wien III.,
  Metternichg. 10" exactly right, where Mistral gave "Vienna - Neustadt -
  Klippipark". It still lost the flourished signature, so no page transcribed by
  machine is citable unread.

- **One page can be re-transcribed on demand and carried onward alone.**
  `POST /api/admin/reocr-page` re-reads a single page on a chosen engine and
  model; that page is then re-translated, the record's searchable PDF rebuilt and
  the record re-embedded. Re-running the record would have re-transcribed a
  hundred other pages on the default engine and re-translated all of them to fix
  one.

- **Line geometry is kept.** Transkribus returns PAGE XML with a polygon and
  baseline per line, which Mistral never gave — it reports bounding boxes for
  figures only. Stored in `page_text.hocr`, so a searchable PDF's invisible text
  layer can sit over the words it transcribes.

- **The eBadatelna scraper works.** Every endpoint it used was wrong, which is
  why it had never fetched a scan: login needs the anti-forgery token as a
  header, `GetSignatureImages` takes a JSON body with an `f`-prefixed id, and the
  OCR search takes a mandatory date range. It also learned to report which scans
  in a volume match a search, and to refuse to read a nil result as an answer on
  an unverified account — before verification every query, `Praha` included,
  returned nothing.

- **A spouse's death date is no longer read as the person's own.** The genealogy
  line for a married person carries the spouse's dates in parentheses, including
  a '+', which the death pattern matched — so the family tree answered 1982 for
  Alexander Czernin, who died at Oxford in 2002. That was his wife's death year.

## v1.0.0 — 16 September 2026

First tagged release. Production had been running the image built on 12
September, because the release policy landed without a tag ever being cut.

- OCR, embedding and translation read their model and limits from the database
  rather than the environment, and a provider is a protocol with an adapter
  rather than a label.
- The admin model form is built from what the backend declares, and a
  fixed-choice setting is a dropdown the backend enforces.
- The pipeline audit became its own service; the file and admin endpoints moved
  out of `ViewerController`.
- The document API states its media type and serves the whole record.
- Translated markdown tables keep their values: a model that narrowed a table's
  delimiter row silently deleted every cell past that width, which emptied the
  Terezín prisoner cards of names, dates and destinations in English while the
  Czech original stayed intact.
- CI runs the scraper-ddb and scraper-findbuch tests, which existed but had no
  stage. The ddb search filtered `mediatype_003` and so never returned archival
  files at all — 5 hits where there were 110, hiding Humprecht Czernin's
  Zuchthaus Brandenburg file and the Nuremberg interrogations of Felix Czernin.
