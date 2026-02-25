-- V3: Searchable PDF pipeline + translation support + ISO language codes

-- Expand job kind constraint to include translate_page and translate_record
ALTER TABLE job DROP CONSTRAINT job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (kind IN (
    'ocr_page_paddle', 'ocr_page_abbyy',
    'build_searchable_pdf', 'extract_entities',
    'generate_thumbs', 'translate_page', 'translate_record'
));

-- English translation of OCR text (per page_text row)
ALTER TABLE page_text ADD COLUMN text_en TEXT;

-- English translation of record metadata
ALTER TABLE record ADD COLUMN title_en TEXT;
ALTER TABLE record ADD COLUMN description_en TEXT;

-- Normalize record.lang to ISO 639-1 two-letter codes
UPDATE record SET lang = 'de' WHERE lang = 'german';
UPDATE record SET lang = 'cs' WHERE lang = 'czech';
UPDATE record SET lang = 'en' WHERE lang = 'english';
UPDATE record SET lang = 'fr' WHERE lang = 'french';
UPDATE record SET lang = 'pl' WHERE lang = 'polish';

-- Metadata language (catalog/archive language, set by scraper)
ALTER TABLE record ADD COLUMN metadata_lang TEXT;
UPDATE record SET metadata_lang = 'cs' WHERE source_system LIKE '%nacr.cz%';

-- Enforce 2-char ISO 639-1 codes going forward
ALTER TABLE record ADD CONSTRAINT record_lang_iso CHECK (lang IS NULL OR char_length(lang) = 2);
ALTER TABLE record ADD CONSTRAINT record_metadata_lang_iso CHECK (metadata_lang IS NULL OR char_length(metadata_lang) = 2);
