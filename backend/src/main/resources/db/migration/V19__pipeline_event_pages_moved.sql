-- pipeline_event.event names what happened to a record's pipeline. Moving pages between records
-- is not a stage running, but it changes what the record holds, and it belongs in the same history.
ALTER TABLE pipeline_event DROP CONSTRAINT pipeline_event_event_check;
ALTER TABLE pipeline_event ADD CONSTRAINT pipeline_event_event_check
  CHECK (event = ANY (ARRAY['started', 'completed', 'failed', 'admin_reset', 'replace_started',
                            'repair_started', 'pages_moved']));
