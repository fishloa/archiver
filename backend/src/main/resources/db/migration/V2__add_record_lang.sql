-- Add language column to record table.
-- The scraper sets this per-record; it's passed through to OCR jobs via job.payload.
ALTER TABLE record ADD COLUMN lang TEXT;

-- All existing records are from Czech archives with German documents.
UPDATE record SET lang = 'german';
