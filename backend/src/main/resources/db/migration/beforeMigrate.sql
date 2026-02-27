-- Ensure pgvector extension is available before V7 runs.
-- Idempotent: safe to run on every startup.
CREATE EXTENSION IF NOT EXISTS vector;
