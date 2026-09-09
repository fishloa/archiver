-- Operator gates: pause a stage without stopping the pipeline.
--
-- A gate holds work at a stage rather than cancelling it. Jobs of a paused kind stay
-- pending and accumulate; workers simply do not claim them. Opening the gate releases the
-- backlog with nothing lost and nothing re-queued by hand.
--
-- The need is concrete: a bad translation model, an engine returning nonsense, or a
-- provider outage should be stoppable in one call. Before this the only ways to stop a
-- stage were to scale its workers to zero (which loses in-flight claims) or to cancel jobs
-- (which loses the queue).

CREATE TABLE pipeline_gate (
    kind        TEXT PRIMARY KEY,
    paused      BOOLEAN NOT NULL DEFAULT false,
    reason      TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT
);

COMMENT ON TABLE pipeline_gate IS
    'Per job-kind pause switch. A paused kind is not claimed by any worker; its jobs queue '
    'up until the gate is opened. Absence of a row means open.';
