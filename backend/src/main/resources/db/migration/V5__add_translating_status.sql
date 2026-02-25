-- Add 'translating', 'complete', and 'error' to the record status check constraint
ALTER TABLE record DROP CONSTRAINT record_status_check;

ALTER TABLE record ADD CONSTRAINT record_status_check CHECK (
    status IN (
        'ingesting', 'ingested',
        'ocr_pending', 'ocr_in_progress', 'ocr_done',
        'pdf_pending', 'pdf_done',
        'translating',
        'entities_pending', 'entities_done',
        'complete', 'error'
    )
);
