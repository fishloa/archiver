-- provider was free text and nothing read it.
--
-- Every translation row became a chat request submitted through Mistral's batch API whatever the
-- row said, so a row pointing at an OpenAI-compatible endpoint would have had POST /v1/files and
-- POST /v1/batch/jobs fired at it and failed every job. The deepinfra translation row seeded in V9
-- is exactly that shape; it has never been enabled.
--
-- provider now names a protocol with an adapter behind it, and the admin API refuses anything
-- else. Existing values are provider *brands*, so map them onto the protocol each one actually
-- speaks.

UPDATE ai_implementation SET provider = 'mistral-batch', updated_at = now()
WHERE provider = 'mistral';

-- DeepInfra, vLLM, Ollama and OpenAI itself are all the same wire protocol.
UPDATE ai_implementation SET provider = 'openai', updated_at = now()
WHERE provider = 'deepinfra';

-- 'legacy' is not a protocol and never was: it marks translations written before per-model
-- attribution existed, has no endpoint and no credential, and is disabled. It is left as it is
-- rather than claiming an adapter it does not have — the runtime skips a row whose provider it
-- does not implement, and this row is never dispatched.

DO $$
DECLARE
    unmapped int;
BEGIN
    SELECT count(*) INTO unmapped
    FROM ai_implementation
    WHERE enabled AND provider NOT IN ('mistral-batch', 'openai');

    IF unmapped > 0 THEN
        RAISE WARNING 'ai_implementation has % enabled row(s) with a provider this build has no adapter for; they will be skipped', unmapped;
    END IF;
END $$;
