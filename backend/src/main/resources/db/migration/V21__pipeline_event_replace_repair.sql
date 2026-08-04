-- Allow 'replace_started' (replaceAllPages) and 'repair_started' (repairRecord) as
-- pipeline_event event types. The latter was already used by IngestService.repairRecord()
-- but was never actually a valid value -- that code path would have failed the same way.
ALTER TABLE pipeline_event DROP CONSTRAINT IF EXISTS pipeline_event_event_check;
ALTER TABLE pipeline_event ADD CONSTRAINT pipeline_event_event_check
  CHECK (event IN ('started', 'completed', 'failed', 'admin_reset', 'replace_started', 'repair_started'));
