-- Entity extraction never ran.
--
-- The tables, the job kind, the record statuses and the worker endpoint were all built and none of
-- them was ever used: entity_hit holds no rows, no extract_entities job was ever created, and no
-- record reached entities_pending or entities_done. Person matching does the job it was meant for.
--
-- Kept in the codebase it was a standing invitation to mistake it for working machinery, and its
-- statuses were being counted in the pipeline dashboard's "complete" total, where they could only
-- ever contribute zero.

DROP TABLE IF EXISTS evidence;
DROP TABLE IF EXISTS entity_hit;

-- The job kind goes with them. Nothing has ever carried it, so nothing is orphaned.
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (kind = ANY (ARRAY[
    'ocr_page_paddle'::text,
    'ocr_page_abbyy'::text,
    'ocr_page_qwen3vl'::text,
    'ocr_page_claude'::text,
    'ocr_page_mistral'::text,
    'build_searchable_pdf'::text,
    'generate_thumbs'::text,
    'translate_page'::text,
    'translate_page_upgrade'::text,
    'translate_record'::text,
    'embed_record'::text,
    'match_persons'::text
]));
