-- Record metadata translation, kept per model like page_translation.
--
-- Titles were translated once by the HTTP worker and written straight over record.title_en, with
-- no record of which model produced them and no way for a better one to replace a worse one. The
-- result: 2,257 of 2,621 records (86%) carry "STATE SECRETARY FOR THE RUSSIAN PROTECTOR IN THINGS
-- AND IN MORAVA" — ŘÍŠSKÉHO read as Russian, ČECHÁCH as things — on the cover sheet of every
-- extract. The all-caps catalogue headings defeated the model; the lower-case descriptions beside
-- them came through correctly, which is why only titles are wrong.
--
-- record.title_en / description_en stay as the cache the UI and search read; this table is the
-- history behind them, and the ranking decides which model's version the cache holds.

CREATE TABLE record_translation (
    id              BIGSERIAL PRIMARY KEY,
    record_id       BIGINT NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    model           TEXT   NOT NULL,
    title_en        TEXT,
    description_en  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (record_id, model)
);

CREATE INDEX idx_record_translation_record ON record_translation (record_id);

-- Everything already translated is attributed to the model that actually produced it, so the
-- first better translation outranks it rather than competing with an unknown.
INSERT INTO record_translation (record_id, model, title_en, description_en, created_at)
SELECT id, 'google/gemma-4-31B-it', title_en, description_en, coalesce(updated_at, now())
FROM record
WHERE (title_en IS NOT NULL AND title_en <> '')
   OR (description_en IS NOT NULL AND description_en <> '');
