-- Chunks are regenerated wholesale by the re-embed that follows this migration:
-- heading-aware chunking changes their content and boundaries, and the embedding
-- model changes from BGE-M3 to Qwen3-Embedding-8B. Nothing here is worth
-- preserving, so the column is replaced rather than converted.
--
-- halfvec (fp16) was measured against fp32 on this archive's own vectors:
-- identical R@1/R@5/MRR, max per-component error 0.000121. It halves the bytes.
--
-- The HNSW index is deliberately NOT recreated here. Building it after the bulk
-- load is much faster than maintaining it across 87k inserts, so it is a manual
-- post-step once re-embedding is complete.

DROP INDEX IF EXISTS idx_text_chunk_embedding;

ALTER TABLE text_chunk
    DROP COLUMN embedding,
    ADD COLUMN embedding halfvec(1024),
    ADD COLUMN heading text NOT NULL DEFAULT '',
    ADD COLUMN content_type text NOT NULL DEFAULT 'text/plain';

ALTER TABLE text_chunk
    ADD CONSTRAINT text_chunk_content_type_check
    CHECK (content_type ~ '^[a-z]+/[a-z0-9.+-]+$');

COMMENT ON COLUMN text_chunk.heading IS
    'Markdown heading path of the section this chunk came from, empty for plain text.';
