-- Switch from OpenAI text-embedding-3-small (1536-dim) to BGE-M3 (1024-dim).
-- All existing embeddings must be regenerated, so we delete first then resize.

DROP INDEX IF EXISTS idx_text_chunk_embedding;
DELETE FROM text_chunk;
ALTER TABLE text_chunk ALTER COLUMN embedding TYPE vector(1024);
CREATE INDEX idx_text_chunk_embedding ON text_chunk USING hnsw (embedding vector_cosine_ops);
