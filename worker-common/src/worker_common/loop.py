"""Reusable SSE-driven main loop for pipeline workers.

All workers follow the same pattern: drain pending jobs, connect to SSE
for real-time notifications, drain again on each event, reconnect with
backoff on failure. The SSE read_timeout ensures periodic polling even
when no events arrive.
"""

import json
import logging
import time
from collections.abc import Callable, Sequence

log = logging.getLogger(__name__)


def wait_for_backend(client, job_kind: str, max_retries: int = 30, delay: int = 5):
    """Wait for the backend to become reachable before proceeding."""
    for attempt in range(1, max_retries + 1):
        try:
            client.claim_job(job_kind)
            log.info("Backend reachable")
            return
        except Exception:
            log.info("Waiting for backend (%d/%d)...", attempt, max_retries)
            time.sleep(delay)
    raise RuntimeError(f"Backend not reachable after {max_retries * delay}s")


def drain_jobs(
    client,
    job_kinds: Sequence[str],
    process_fn: Callable[[dict], None],
) -> int:
    """Claim and process all available jobs. Returns number processed.

    Args:
        client: ProcessorClient (or subclass) with claim_job/fail_job.
        job_kinds: Job kind strings to claim (e.g. ["ocr_page_paddle"]).
        process_fn: Callback taking a single job dict. Should call
            client.complete_job() on success.
    """
    count = 0
    for kind in job_kinds:
        while True:
            job = client.claim_job(kind)
            if job is None:
                break
            try:
                process_fn(job)
                count += 1
            except Exception as e:
                log.error("Job %d failed: %s", job["id"], e, exc_info=True)
                try:
                    client.fail_job(job["id"], str(e)[:500])
                except Exception:
                    log.error("Failed to report job failure", exc_info=True)
    return count


def run_sse_loop(
    client,
    job_kinds: Sequence[str],
    process_fn: Callable[[dict], None],
    poll_interval: int,
):
    """Main loop: subscribe to SSE, drain jobs on each event, reconnect on failure.

    Uses poll_interval as the SSE read timeout so the connection expires
    periodically, guaranteeing jobs are drained even if SSE events are
    missed or filtered.

    Args:
        client: ProcessorClient (or subclass).
        job_kinds: Job kind strings this worker handles.
        process_fn: Callback taking a single job dict.
        poll_interval: Seconds between poll cycles (also SSE read timeout).
    """
    jobs_processed = 0
    reconnect_delay = 1
    kind_set = set(job_kinds)

    while True:
        jobs_processed += drain_jobs(client, job_kinds, process_fn)

        try:
            log.info("Connecting to SSE job events stream...")
            with client.job_events(read_timeout=float(poll_interval), kinds=list(job_kinds)) as events:
                reconnect_delay = 1
                log.info("SSE connected, waiting for events (poll every %ds)", poll_interval)
                for event in events:
                    if event.event == "job":
                        data = json.loads(event.data)
                        kind = data.get("kind", "")
                        if kind in kind_set:
                            log.info("SSE: job event for %s, draining queue", kind)
                            jobs_processed += drain_jobs(client, job_kinds, process_fn)
        except KeyboardInterrupt:
            log.info("Shutting down (processed %d jobs)", jobs_processed)
            raise
        except Exception as e:
            log.warning("SSE disconnected: %s — polling fallback", e)
            jobs_processed += drain_jobs(client, job_kinds, process_fn)
            time.sleep(min(reconnect_delay, 5))
            reconnect_delay = min(reconnect_delay * 2, poll_interval)
