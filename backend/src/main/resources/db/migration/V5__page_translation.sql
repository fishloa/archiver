-- Every translation, kept per model.
--
-- page_text.text_en holds one translation and nothing records which model produced it. That is
-- the same shape of mistake page_text itself had for OCR: several engines writing to one place,
-- no record of provenance, and a reader with no way to tell a good transcription from a bad one.
-- It ended with the archive serving its worst OCR for 65,221 pages.
--
-- So translations accumulate here instead, one row per page per model, and text_en becomes a
-- cache of whichever is preferred. A page translated cheaply today can be upgraded later without
-- losing what it had, and re-running an upgrade that has already been done is refusable because
-- the row is already there.

CREATE TABLE page_translation (
    id          BIGSERIAL PRIMARY KEY,
    page_id     BIGINT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    model       TEXT   NOT NULL,
    text_en     TEXT   NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, model)
);

CREATE INDEX idx_page_translation_page ON page_translation(page_id);
CREATE INDEX idx_page_translation_model ON page_translation(model);

COMMENT ON TABLE page_translation IS
    'One row per page per translation model. page_text.text_en caches whichever is preferred; '
    'this is the record of what each model actually produced.';

-- Preserve translations already made. Their model was not recorded, hence the label: they
-- predate this table and were produced by whatever was configured at the time.
INSERT INTO page_translation (page_id, model, text_en, created_at)
SELECT pt.page_id, 'legacy', pt.text_en, pt.created_at
FROM page_text pt
WHERE pt.text_en IS NOT NULL AND pt.text_en <> ''
ON CONFLICT (page_id, model) DO NOTHING;
