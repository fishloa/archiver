-- A transcription is stored exactly as the engine produced it. When a person corrects one by
-- hand (a misread year, a mistranslated title), the change is made in place and recorded here:
-- what the text said, what it says now, who changed it, and why. The audit row outlives the
-- page it concerns.
CREATE TABLE manual_correction (
    id            bigserial PRIMARY KEY,
    record_id     bigint NOT NULL REFERENCES record (id) ON DELETE CASCADE,
    page_id       bigint REFERENCES page (id) ON DELETE SET NULL,
    field         text NOT NULL
        CHECK (field IN ('text_raw', 'text_en', 'title', 'description', 'title_en',
                         'description_en')),
    old_text      text NOT NULL,
    new_text      text NOT NULL,
    reason        text NOT NULL,
    corrected_by  text NOT NULL,
    corrected_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_manual_correction_record ON manual_correction (record_id);
CREATE INDEX idx_manual_correction_page ON manual_correction (page_id);
