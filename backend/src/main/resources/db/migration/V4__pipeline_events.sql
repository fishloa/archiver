CREATE TABLE pipeline_event (
    id          BIGSERIAL PRIMARY KEY,
    record_id   BIGINT      NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    stage       TEXT        NOT NULL,
    event       TEXT        NOT NULL CHECK (event IN ('started', 'completed', 'failed')),
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pipeline_event_record_id ON pipeline_event(record_id);

-- Backfill pipeline events from existing job and record data
-- This reconstructs the timeline for records that were processed before this table existed.

-- Ingest started: use record.created_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'ingest', 'started', 'backfill', r.created_at
FROM record r
WHERE r.status NOT IN ('ingesting');

-- Ingest completed: use the earliest OCR job created_at (that's when ingest finished and OCR began)
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'ingest', 'completed',
       'backfill: ' || r.page_count || ' pages',
       COALESCE(
           (SELECT min(j.created_at) FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%'),
           r.updated_at
       )
FROM record r
WHERE r.status NOT IN ('ingesting', 'ingested');

-- OCR started: use the earliest OCR job created_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'ocr', 'started',
       'backfill: ' || (SELECT count(*) FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%') || ' jobs',
       (SELECT min(j.created_at) FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%')
FROM record r
WHERE r.status NOT IN ('ingesting', 'ingested', 'ocr_pending')
  AND EXISTS (SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%');

-- OCR completed: use the latest OCR job finished_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'ocr', 'completed', 'backfill',
       (SELECT max(j.finished_at) FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%' AND j.status = 'completed')
FROM record r
WHERE r.status NOT IN ('ingesting', 'ingested', 'ocr_pending')
  AND NOT EXISTS (
      SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%' AND j.status NOT IN ('completed', 'failed')
  )
  AND EXISTS (
      SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind LIKE 'ocr_page_%' AND j.status = 'completed'
  );

-- PDF build started: use the build_searchable_pdf job created_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'pdf_build', 'started', 'backfill',
       (SELECT min(j.created_at) FROM job j WHERE j.record_id = r.id AND j.kind = 'build_searchable_pdf')
FROM record r
WHERE EXISTS (SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind = 'build_searchable_pdf');

-- PDF build completed: use the build_searchable_pdf job finished_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'pdf_build', 'completed',
       'backfill: attachment_id=' || COALESCE(r.pdf_attachment_id::text, '?'),
       (SELECT max(j.finished_at) FROM job j WHERE j.record_id = r.id AND j.kind = 'build_searchable_pdf' AND j.status = 'completed')
FROM record r
WHERE r.status IN ('pdf_done', 'complete')
  AND EXISTS (SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind = 'build_searchable_pdf' AND j.status = 'completed');

-- Translation started: use the earliest translate job created_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'translation', 'started',
       'backfill: ' || (SELECT count(*) FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record')) || ' jobs',
       (SELECT min(j.created_at) FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record'))
FROM record r
WHERE EXISTS (SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record'));

-- Translation completed: use the latest translate job finished_at
INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)
SELECT r.id, 'translation', 'completed', 'backfill',
       (SELECT max(j.finished_at) FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record') AND j.status = 'completed')
FROM record r
WHERE NOT EXISTS (
    SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record') AND j.status NOT IN ('completed', 'failed')
)
AND EXISTS (
    SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind IN ('translate_page', 'translate_record') AND j.status = 'completed'
);
