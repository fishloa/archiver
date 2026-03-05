-- Add 'matching' to the record status check constraint
ALTER TABLE record DROP CONSTRAINT record_status_check;
ALTER TABLE record ADD CONSTRAINT record_status_check CHECK (
    status IN (
        'ingesting', 'ingested',
        'ocr_pending', 'ocr_in_progress', 'ocr_done',
        'pdf_pending', 'pdf_done',
        'translating',
        'embedding',
        'matching',
        'entities_pending', 'entities_done',
        'complete', 'error'
    )
);

-- Add 'match_persons' to the job kind check constraint
ALTER TABLE job DROP CONSTRAINT job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (
    kind IN (
        'ocr_page_paddle', 'ocr_page_abbyy', 'ocr_page_qwen3vl',
        'build_searchable_pdf',
        'extract_entities', 'generate_thumbs',
        'translate_page', 'translate_record',
        'embed_record',
        'match_persons'
    )
);
