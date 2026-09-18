# Changelog

Releases are tags. A tagged build moves `:latest` and deploys production; an
untagged build on main moves `:test` and deploys the test stack.

**A release must be tagged on its own commit.** Jenkins tracks what it has built
by commit SHA, so a tag placed on a commit that has already been built is not a
new revision, no build is triggered, and the release silently never happens —
which is exactly what v1.0.0 did on its first attempt. Add the entry below,
commit, then tag that commit.

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
