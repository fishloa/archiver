-- The collection Transkribus documents are created in.
--
-- TranskribusConfig.collectionId() falls back to 0, documented as "the account's first collection".
-- The TrpServer API does not read 0 that way -- it answers "HTTP 400: Bad or no collection ID" and
-- every recognition job fails. Held as data so a rebuilt database has a working engine.
UPDATE ai_implementation
   SET settings = jsonb_set(settings, '{collId}', '2516426')
 WHERE provider = 'transkribus'
   AND settings->>'collId' IS NULL;
