CREATE INDEX IF NOT EXISTS idx_text_chunk_content_trgm
    ON text_chunk USING gin (lower(content) gin_trgm_ops);
