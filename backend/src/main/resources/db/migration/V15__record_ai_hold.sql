-- Freeze a record's AI processing.
--
-- A record can be left in a state where every further job is waste: a transcription overwritten by
-- a worse one, a page emptied by a failed re-OCR. Translation, embedding and matching are charged
-- per call, so the pipeline will happily spend money reproducing a broken document while someone
-- works out how to fix it.
--
-- Held like a paused stage rather than a cancelled one: jobs still queue, they are simply not
-- claimed, so nothing is lost and the record resumes where it stopped.
ALTER TABLE record ADD COLUMN ai_held_at timestamptz;
ALTER TABLE record ADD COLUMN ai_hold_reason text;

-- Every claim query filters on this, so it is worth an index on the few rows that are held.
CREATE INDEX idx_record_ai_held ON record (id) WHERE ai_held_at IS NOT NULL;
