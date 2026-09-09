-- Allow the on-demand translation upgrade job kind.
--
-- A separate kind from translate_page because a provider batch carries exactly one model, and
-- because the upgrade queue should be holdable and drainable independently of the bulk run —
-- the bulk model is cheap and runs over the whole archive, the upgrade model costs about ten
-- times as much and runs only where someone asks for it.

ALTER TABLE job DROP CONSTRAINT IF EXISTS job_kind_check;

ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (kind = ANY (ARRAY[
    'ocr_page_paddle',
    'ocr_page_abbyy',
    'ocr_page_qwen3vl',
    'ocr_page_claude',
    'ocr_page_mistral',
    'build_searchable_pdf',
    'extract_entities',
    'generate_thumbs',
    'translate_page',
    'translate_page_upgrade',
    'translate_record',
    'embed_record',
    'match_persons'
]::text[]));
