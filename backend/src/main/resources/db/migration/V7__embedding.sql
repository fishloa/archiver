-- Enable pgvector extension for semantic search
CREATE EXTENSION IF NOT EXISTS vector;

-- Add 'embedding' to the record status check constraint
ALTER TABLE record DROP CONSTRAINT record_status_check;
ALTER TABLE record ADD CONSTRAINT record_status_check CHECK (
    status IN (
        'ingesting', 'ingested',
        'ocr_pending', 'ocr_in_progress', 'ocr_done',
        'pdf_pending', 'pdf_done',
        'translating',
        'embedding',
        'entities_pending', 'entities_done',
        'complete', 'error'
    )
);

-- Text chunks with vector embeddings for semantic search
CREATE TABLE text_chunk (
    id         BIGSERIAL PRIMARY KEY,
    record_id  BIGINT  NOT NULL REFERENCES record(id),
    page_id    BIGINT  REFERENCES page(id),
    chunk_index INT    NOT NULL,
    content    TEXT    NOT NULL,
    embedding  vector(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_text_chunk_record ON text_chunk(record_id);
CREATE INDEX idx_text_chunk_embedding ON text_chunk USING hnsw (embedding vector_cosine_ops);
