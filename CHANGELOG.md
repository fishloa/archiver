# Changelog

Releases are tags. A tagged build moves `:latest` and deploys production; an
untagged build on main moves `:test` and deploys the test stack.

**A release must be tagged on its own commit.** Jenkins tracks what it has built
by commit SHA, so a tag placed on a commit that has already been built is not a
new revision, no build is triggered, and the release silently never happens —
which is exactly what v1.0.0 did on its first attempt. Add the entry below,
commit, then tag that commit.

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
