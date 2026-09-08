-- Batched OCR: pages are submitted to Mistral's Batch API in bulk rather than one HTTP
-- call per page.
--
-- The batch layer deliberately knows nothing about records. It takes a flat set of
-- un-OCR'd pages off the queue, submits them together, and writes the results back. Record
-- completion needs no handling here: each page keeps its own `job` row carrying record_id,
-- so when the last page of a record lands, completeJob's autoAdvance sees every page has
-- text and moves the record on. The state machine does the assembly for free.
--
-- A batch is tracked in its own table rather than as a `job` row because the job status
-- vocabulary (pending/claimed/completed/failed) cannot express "submitted, awaiting the
-- provider" without abusing `claimed` and its lease — which is exactly the fragility this
-- replaces.

CREATE TABLE ocr_batch (
    id              BIGSERIAL PRIMARY KEY,
    -- submitting: rows claimed, provider not yet confirmed — a crash here is recoverable
    -- submitted:  provider has it, awaiting results
    -- collected:  every page accounted for, success or failure
    -- failed:     abandoned; its pages were released or failed individually
    status          TEXT NOT NULL CHECK (status IN ('submitting','submitted','collected','failed')),
    page_count      INTEGER NOT NULL,
    input_file_id   TEXT,
    provider_job_id TEXT,
    output_file_id  TEXT,
    succeeded       INTEGER,
    failed          INTEGER,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    last_polled_at  TIMESTAMPTZ,
    collected_at    TIMESTAMPTZ
);

CREATE INDEX idx_ocr_batch_status ON ocr_batch(status);

COMMENT ON TABLE ocr_batch IS
    'One row per submission to the OCR provider''s batch API. Holds all in-flight state, so '
    'a backend restart resumes from the table rather than from memory.';

-- Which batch a page''s OCR job was submitted in. NULL means the job is not at the provider.
ALTER TABLE job ADD COLUMN batch_id BIGINT REFERENCES ocr_batch(id);

CREATE INDEX idx_job_batch_id ON job(batch_id) WHERE batch_id IS NOT NULL;

COMMENT ON COLUMN job.batch_id IS
    'Set while the job is claimed by an OCR batch. Stale-claim recovery must ignore these: a '
    'job at the provider is legitimately claimed for as long as the provider takes, and '
    'releasing it would resubmit pages that are already being billed.';
