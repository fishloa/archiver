-- Which model does which job, and which is preferred.
--
-- Three things were separately hardcoded and had to agree: which provider each pipeline stage
-- called (env vars, read at startup), which model it asked for (more env vars), and which of
-- several stored translations was the better one (a Java list in TranslationModels). They did not
-- agree. The compose file pointed embedding at a local bge-m3 server while the backend built
-- queries for Qwen3; page translation raced a second worker running gemma; and a paid-for
-- mistral-medium upgrade sat unused behind a ranking that read a model's identity out of its bytes.
--
-- One table now answers all three. Rank is the preference order within a capability, lowest first:
-- it decides which implementation new work goes to, and which stored translation outranks another.
--
-- Credentials are deliberately NOT here. The row names an environment variable and the value stays
-- in the deployment, so a database backup carries no keys and an admin reading this table cannot
-- read them either.

CREATE TABLE ai_implementation (
    id              TEXT PRIMARY KEY,
    capability      TEXT    NOT NULL CHECK (capability IN ('OCR', 'TRANSLATION', 'EMBEDDING')),
    provider        TEXT    NOT NULL,
    model           TEXT    NOT NULL,
    base_url        TEXT    NOT NULL,
    endpoint_path   TEXT,
    credential_env  TEXT,
    -- 1 means the provider has no batch API and each item is submitted on its own.
    max_batch_size  INTEGER NOT NULL DEFAULT 1 CHECK (max_batch_size >= 1),
    -- Lowest wins. Unique so the order is always decided, never left to chance.
    rank            INTEGER NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    settings        JSONB   NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (capability, rank)
);

CREATE INDEX idx_ai_implementation_capability ON ai_implementation (capability, rank)
    WHERE enabled;

-- Seeded to reproduce exactly what is running today, so this migration changes no behaviour.
INSERT INTO ai_implementation
    (id, capability, provider, model, base_url, endpoint_path, credential_env,
     max_batch_size, rank, enabled, settings)
VALUES
    ('mistral:mistral-ocr-latest', 'OCR', 'mistral', 'mistral-ocr-latest',
     'https://api.mistral.ai', '/v1/ocr', 'MISTRAL_API_KEY',
     1000, 1, true, '{"maxBatchBytes": 209715200, "pagesPerMinute": 1250}'),

    ('mistral:mistral-medium-latest', 'TRANSLATION', 'mistral', 'mistral-medium-latest',
     'https://api.mistral.ai', '/v1/chat/completions', 'MISTRAL_API_KEY',
     2000, 1, true, '{"tier": "best"}'),

    ('mistral:mistral-small-latest', 'TRANSLATION', 'mistral', 'mistral-small-latest',
     'https://api.mistral.ai', '/v1/chat/completions', 'MISTRAL_API_KEY',
     2000, 2, true, '{"tier": "bulk"}'),

    -- Retired, kept ranked so the translations it produced are still ordered correctly against
    -- the models that replaced it. 2,698 records and a handful of pages still carry its output.
    ('deepinfra:google/gemma-4-31B-it', 'TRANSLATION', 'deepinfra', 'google/gemma-4-31B-it',
     'https://api.deepinfra.com/v1/openai', '/chat/completions', 'TRANSLATE_API_KEY',
     1, 3, false, '{"retired": true}'),

    ('deepinfra:Qwen/Qwen3-Embedding-8B', 'EMBEDDING', 'deepinfra', 'Qwen/Qwen3-Embedding-8B',
     'https://api.deepinfra.com/v1/openai', '/embeddings', 'EMBED_TEI_KEY',
     128, 1, true,
     '{"dimensions": 1024, "queryPrefix": "Instruct: Given a research question, retrieve the archive passage that answers it\nQuery: "}');

-- The legacy marker predates per-model attribution: rows written before page_translation recorded
-- which model produced them. It has no implementation and ranks last.
INSERT INTO ai_implementation
    (id, capability, provider, model, base_url, credential_env, max_batch_size, rank, enabled,
     settings)
VALUES
    ('legacy', 'TRANSLATION', 'legacy', 'legacy', '', NULL, 1, 99, false,
     '{"note": "pre-attribution translations; ranks last"}');
