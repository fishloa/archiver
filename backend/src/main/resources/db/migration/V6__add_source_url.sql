ALTER TABLE record ADD COLUMN source_url TEXT;
ALTER TABLE page ADD COLUMN source_url TEXT;

-- Backfill record source URLs from existing VadeMeCum records
UPDATE record
SET source_url = 'https://vademecum.nacr.cz/vademecum/permalink?xid=' || source_record_id
WHERE source_system = 'vademecum.nacr.cz'
  AND source_url IS NULL;
