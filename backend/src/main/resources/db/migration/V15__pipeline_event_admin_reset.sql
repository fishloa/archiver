-- Allow 'admin_reset' as a pipeline_event event type
ALTER TABLE pipeline_event DROP CONSTRAINT IF EXISTS pipeline_event_event_check;
ALTER TABLE pipeline_event ADD CONSTRAINT pipeline_event_event_check
  CHECK (event IN ('started', 'completed', 'failed', 'admin_reset'));
