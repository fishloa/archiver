# Changelog

Releases are tags. A tagged build moves `:latest` and deploys production; an
untagged build on main moves `:test` and deploys the test stack.

**A release must be tagged on its own commit.** Jenkins tracks what it has built
by commit SHA, so a tag placed on a commit that has already been built is not a
new revision, no build is triggered, and the release silently never happens —
which is exactly what v1.0.0 did on its first attempt. Add the entry below,
commit, then tag that commit.

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
