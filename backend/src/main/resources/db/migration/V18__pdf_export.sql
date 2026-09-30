-- PDF exports: a request for a PDF, built later by a worker and downloaded when it is ready.
--
-- Its own table rather than the pipeline's job table: completing a pipeline job calls the state
-- machine, and an export has no business anywhere near that.
--
-- fingerprint says what the pages WERE when the export was requested (their ids, scans, text and
-- translations, and the record's cover-sheet fields). An export is reused only while the same
-- pages still produce the same fingerprint.
CREATE TABLE pdf_export (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id   bigint NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    variant     text   NOT NULL CHECK (variant IN ('original', 'english', 'side_by_side')),
    page_ids    bigint[] NOT NULL,
    fingerprint text   NOT NULL,
    state       text   NOT NULL DEFAULT 'queued'
                CHECK (state IN ('queued', 'building', 'ready', 'failed', 'expired')),
    path        text,
    bytes       bigint,
    error       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz,
    expires_at  timestamptz
);

-- Claiming and the reaper only ever look at the few rows still in flight.
CREATE INDEX idx_pdf_export_open ON pdf_export (state) WHERE state IN ('queued', 'building');
CREATE INDEX idx_pdf_export_record ON pdf_export (record_id);
