-- Add 'ocr_page_qwen3vl' to the job kind check constraint
ALTER TABLE job DROP CONSTRAINT job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (
    kind IN (
        'ocr_page_paddle', 'ocr_page_abbyy', 'ocr_page_qwen3vl',
        'build_searchable_pdf',
        'extract_entities', 'generate_thumbs',
        'translate_page', 'translate_record',
        'embed_record'
    )
);
