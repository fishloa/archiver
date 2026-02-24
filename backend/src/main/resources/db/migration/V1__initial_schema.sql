-- ==========================================================================
-- V1 — Archiver initial schema
-- ==========================================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- ==========================================================================
-- Enum-like types expressed as CHECK constraints (kept as text columns)
-- ==========================================================================

-- record_status: ingesting, ingested, ocr_pending, ocr_in_progress, ocr_done,
--                pdf_pending, pdf_done, entities_pending, entities_done
-- job_kind:      ocr_page_paddle, ocr_page_abbyy, build_searchable_pdf,
--                extract_entities, generate_thumbs
-- job_status:    pending, claimed, completed, failed

-- ==========================================================================
-- Tables
-- ==========================================================================

CREATE TABLE archive (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL,
    country         TEXT,
    citation_template TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE collection (
    id                  BIGSERIAL PRIMARY KEY,
    archive_id          BIGINT      NOT NULL REFERENCES archive(id),
    name                TEXT        NOT NULL,
    code                TEXT,
    raw_source_metadata JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_collection_archive_id ON collection(archive_id);

CREATE TABLE record (
    id                  BIGSERIAL PRIMARY KEY,
    archive_id          BIGINT      NOT NULL REFERENCES archive(id),
    collection_id       BIGINT      REFERENCES collection(id),
    source_system       TEXT        NOT NULL,
    source_record_id    TEXT        NOT NULL,
    title               TEXT,
    description         TEXT,
    date_range_text     TEXT,
    date_start_year     INT,
    date_end_year       INT,
    reference_code      TEXT,
    inventory_number    TEXT,
    call_number         TEXT,
    container_type      TEXT,
    container_number    TEXT,
    finding_aid_number  TEXT,
    index_terms         JSONB,
    raw_source_metadata JSONB,
    pdf_attachment_id   BIGINT,
    attachment_count    INT         NOT NULL DEFAULT 0,
    page_count          INT         NOT NULL DEFAULT 0,
    status              TEXT        NOT NULL DEFAULT 'ingesting'
                        CHECK (status IN (
                            'ingesting', 'ingested',
                            'ocr_pending', 'ocr_in_progress', 'ocr_done',
                            'pdf_pending', 'pdf_done',
                            'entities_pending', 'entities_done'
                        )),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_record_source ON record(source_system, source_record_id);
CREATE INDEX idx_record_archive_id ON record(archive_id);
CREATE INDEX idx_record_collection_id ON record(collection_id);
CREATE INDEX idx_record_status ON record(status);

CREATE TABLE record_attribute (
    id          BIGSERIAL PRIMARY KEY,
    record_id   BIGINT  NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    key         TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_record_attribute_record_id ON record_attribute(record_id);

CREATE TABLE attachment (
    id          BIGSERIAL PRIMARY KEY,
    record_id   BIGINT      NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    role        TEXT        NOT NULL,
    path        TEXT        NOT NULL,
    sha256      TEXT,
    mime        TEXT,
    bytes       BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachment_record_id ON attachment(record_id);

-- Add FK from record.pdf_attachment_id now that attachment exists
ALTER TABLE record
    ADD CONSTRAINT fk_record_pdf_attachment
    FOREIGN KEY (pdf_attachment_id) REFERENCES attachment(id);

CREATE TABLE page (
    id              BIGSERIAL PRIMARY KEY,
    record_id       BIGINT  NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    seq             INT     NOT NULL,
    attachment_id   BIGINT  NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,
    page_label      TEXT,
    width           INT,
    height          INT
);

CREATE UNIQUE INDEX idx_page_record_seq ON page(record_id, seq);
CREATE INDEX idx_page_attachment_id ON page(attachment_id);

CREATE TABLE job (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT        NOT NULL
                CHECK (kind IN (
                    'ocr_page_paddle', 'ocr_page_abbyy',
                    'build_searchable_pdf', 'extract_entities',
                    'generate_thumbs'
                )),
    record_id   BIGINT      REFERENCES record(id) ON DELETE CASCADE,
    page_id     BIGINT      REFERENCES page(id) ON DELETE CASCADE,
    payload     JSONB,
    status      TEXT        NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'claimed', 'completed', 'failed')),
    attempts    INT         NOT NULL DEFAULT 0,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_job_status_kind ON job(status, kind);
CREATE INDEX idx_job_record_id ON job(record_id);
CREATE INDEX idx_job_page_id ON job(page_id);

CREATE TABLE processing_run (
    id          BIGSERIAL PRIMARY KEY,
    job_id      BIGINT      NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    worker_id   TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status      TEXT        NOT NULL DEFAULT 'running'
                CHECK (status IN ('running', 'completed', 'failed')),
    error       TEXT,
    metrics     JSONB
);

CREATE INDEX idx_processing_run_job_id ON processing_run(job_id);

CREATE TABLE page_text (
    id          BIGSERIAL PRIMARY KEY,
    page_id     BIGINT  NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    engine      TEXT    NOT NULL,
    confidence  REAL,
    text_raw    TEXT    NOT NULL,
    text_norm   TEXT    GENERATED ALWAYS AS (unaccent(lower(text_raw))) STORED,
    hocr        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_page_text_page_id ON page_text(page_id);
CREATE INDEX idx_page_text_text_norm_trgm ON page_text USING gin (text_norm gin_trgm_ops);

CREATE TABLE page_search (
    id              BIGSERIAL PRIMARY KEY,
    page_id         BIGINT  NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    best_engine     TEXT    NOT NULL,
    best_text_norm  TEXT    NOT NULL,
    tsv             TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', best_text_norm)) STORED
);

CREATE UNIQUE INDEX idx_page_search_page_id ON page_search(page_id);
CREATE INDEX idx_page_search_tsv ON page_search USING gin (tsv);
CREATE INDEX idx_page_search_trgm ON page_search USING gin (best_text_norm gin_trgm_ops);

CREATE TABLE entity_hit (
    id          BIGSERIAL PRIMARY KEY,
    page_id     BIGINT  NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    entity_type TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    confidence  REAL,
    start_offset INT,
    end_offset   INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entity_hit_page_id ON entity_hit(page_id);
CREATE INDEX idx_entity_hit_type_value ON entity_hit(entity_type, value);
CREATE INDEX idx_entity_hit_value_trgm ON entity_hit USING gin (value gin_trgm_ops);

CREATE TABLE evidence (
    id              BIGSERIAL PRIMARY KEY,
    entity_hit_id   BIGINT  NOT NULL REFERENCES entity_hit(id) ON DELETE CASCADE,
    page_id         BIGINT  NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    snippet         TEXT,
    bounding_box    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_entity_hit_id ON evidence(entity_hit_id);
CREATE INDEX idx_evidence_page_id ON evidence(page_id);
