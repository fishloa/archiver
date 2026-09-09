-- Generalise the batch table: OCR was simply the first stage to use a provider's batch API,
-- not the only one that can. Translation is the immediate case — 128,484 pages at 1.9s each
-- interactively, against a fraction of that batched, at half the price.
--
-- One table with a job_kind rather than one table per stage, so the machinery that took a
-- long night to get right — orphan adoption instead of double billing, the
-- completed+failed==page_count assertion that stops pages vanishing, byte-budget sizing,
-- gate enforcement — is inherited by every future stage rather than copied and quietly
-- diverging.

ALTER TABLE ocr_batch RENAME TO provider_batch;

ALTER TABLE provider_batch
    ADD COLUMN job_kind TEXT NOT NULL DEFAULT 'ocr_page_mistral';

-- Existing rows are all OCR; the default above already labels them correctly.
ALTER TABLE provider_batch ALTER COLUMN job_kind DROP DEFAULT;

CREATE INDEX idx_provider_batch_kind_status ON provider_batch(job_kind, status);

COMMENT ON TABLE provider_batch IS
    'One row per submission to a provider''s batch API, for any pipeline stage. Holds all '
    'in-flight state, so a restart resumes from the table rather than from memory.';
COMMENT ON COLUMN provider_batch.job_kind IS
    'The job kind this batch carries, e.g. ocr_page_mistral or translate_page.';
