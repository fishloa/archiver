CREATE TABLE page_person_match (
    id          BIGSERIAL PRIMARY KEY,
    page_id     BIGINT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    person_id   INT NOT NULL,
    person_name TEXT NOT NULL,
    score       REAL NOT NULL,
    context     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, person_id)
);
CREATE INDEX idx_page_person_match_page ON page_person_match(page_id);
CREATE INDEX idx_page_person_match_person ON page_person_match(person_id);
