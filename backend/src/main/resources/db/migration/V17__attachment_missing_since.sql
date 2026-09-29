-- When the storage migration went looking for an attachment's file and found nothing.
--
-- Set once, so the migration counts the row instead of retrying it for ever, and the gap stays
-- visible rather than disappearing into a log line. NULL for every file that exists.
ALTER TABLE attachment ADD COLUMN missing_since timestamptz;
