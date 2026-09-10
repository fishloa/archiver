-- Which translation model a record's pages should be translated with, chosen once.
--
-- The pipeline enqueues translate_page on entering 'translating', always at bulk quality. A
-- record that wanted the better model therefore had to be translated twice: once by the bulk
-- pass, then again by an upgrade queued behind it. That is not merely wasteful — the two race.
-- Avoiding the duplicate meant holding the translate_page gate before OCR landed and swapping
-- the jobs inside a 15-second tick, and losing that race cost a redundant provider batch on
-- record 3797 and four more across records 3798-3800 (cancelled before they billed).
--
-- 'bulk' keeps the existing behaviour and stays the default: the archive's 128,484 pages were
-- translated that way and re-running them is a deliberate act, not a migration.

ALTER TABLE record
    ADD COLUMN translation_quality TEXT NOT NULL DEFAULT 'bulk';

ALTER TABLE record
    ADD CONSTRAINT record_translation_quality_check
    CHECK (translation_quality IN ('bulk', 'best'));

COMMENT ON COLUMN record.translation_quality IS
    'bulk = translate_page (cheap model); best = translate_page_upgrade (upgrade model). '
    'Chosen at ingest and honoured by PipelineStateMachine, so a record is translated once.';
