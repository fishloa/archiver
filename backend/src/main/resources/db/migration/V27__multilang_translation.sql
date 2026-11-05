ALTER TABLE page_translation RENAME COLUMN text_en TO text;

ALTER TABLE page_translation
    ADD COLUMN lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en', 'de', 'cs'));

ALTER TABLE page_translation ADD COLUMN source_md5 text;

ALTER TABLE page_translation DROP CONSTRAINT page_translation_page_id_model_key;
ALTER TABLE page_translation
    ADD CONSTRAINT page_translation_page_id_lang_model_key UNIQUE (page_id, lang, model);

ALTER TABLE pdf_export
    ADD COLUMN lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en', 'de', 'cs'));
