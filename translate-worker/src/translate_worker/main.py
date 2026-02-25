"""Translate worker main loop.

Claims translate_page and translate_record jobs from the backend,
translates text from German/Czech to English using Helsinki-NLP
MarianMT models, and posts the results back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import json
import logging
import time

log = logging.getLogger(__name__)

JOB_KINDS = ("translate_page", "translate_record")


def process_translate_page(client, translator, job: dict) -> None:
    """Process a single translate_page job."""
    job_id = job["id"]
    page_id = job["pageId"]
    record_id = job.get("recordId", "?")

    log.info("Processing translate_page job %d: page %d (record %s)", job_id, page_id, record_id)

    page_data = client.get_page_text(page_id)
    text = page_data.get("text") or ""

    if not text.strip():
        log.info("  Page %d has no text, skipping translation", page_id)
        client.complete_job(job_id, result="no_text")
        return

    # Auto-detect source language (could be German, Czech, or other)
    translated = translator.translate(text)

    client.submit_page_translation(page_id, translated)
    client.complete_job(job_id)
    log.info("  Job %d completed: %d chars -> %d chars", job_id, len(text), len(translated))


def process_translate_record(client, translator, job: dict) -> None:
    """Process a single translate_record job."""
    job_id = job["id"]
    record_id = job.get("recordId")

    if record_id is None:
        # Try to get recordId from payload
        payload = job.get("payload")
        if payload:
            try:
                data = json.loads(payload) if isinstance(payload, str) else payload
                record_id = data.get("recordId")
            except (json.JSONDecodeError, AttributeError):
                pass

    if record_id is None:
        raise ValueError(f"Job {job_id} has no recordId")

    log.info("Processing translate_record job %d: record %d", job_id, record_id)

    record = client.get_record(record_id)
    title = record.get("title") or ""
    description = record.get("description") or ""

    if not title.strip() and not description.strip():
        log.info("  Record %d has no title/description, skipping translation", record_id)
        client.complete_job(job_id, result="no_text")
        return

    # Auto-detect language for metadata (varies by archive)
    title_en = translator.translate(title) if title.strip() else ""
    description_en = translator.translate(description) if description.strip() else ""

    client.submit_record_translation(record_id, title_en, description_en)
    client.complete_job(job_id)
    log.info(
        "  Job %d completed: title %d->%d chars, desc %d->%d chars",
        job_id,
        len(title),
        len(title_en),
        len(description),
        len(description_en),
    )


def process_job(client, translator, job: dict) -> None:
    """Route a job to the appropriate handler."""
    kind = job.get("kind", "")
    if kind == "translate_page":
        process_translate_page(client, translator, job)
    elif kind == "translate_record":
        process_translate_record(client, translator, job)
    else:
        raise ValueError(f"Unknown job kind: {kind}")


def drain_jobs(client, translator) -> int:
    """Claim and process all available jobs for both kinds. Returns number processed."""
    count = 0
    for kind in JOB_KINDS:
        while True:
            job = client.claim_job(kind)
            if job is None:
                break
            try:
                process_job(client, translator, job)
                count += 1
            except Exception as e:
                log.error("Job %d failed: %s", job["id"], e, exc_info=True)
                try:
                    client.fail_job(job["id"], str(e)[:500])
                except Exception:
                    log.error("Failed to report job failure", exc_info=True)
    return count


def wait_for_backend(client, max_retries=30, delay=5):
    """Wait for the backend to become reachable before proceeding."""
    for attempt in range(1, max_retries + 1):
        try:
            client.claim_job(JOB_KINDS[0])
            log.info("Backend reachable")
            return
        except Exception:
            log.info("Waiting for backend (%d/%d)...", attempt, max_retries)
            time.sleep(delay)
    raise RuntimeError(f"Backend not reachable after {max_retries * delay}s")


def run_sse_loop(client, translator, poll_interval: int):
    """Main loop: subscribe to SSE, drain jobs on each event, reconnect on failure."""
    jobs_processed = 0
    reconnect_delay = 1

    while True:
        # Drain any jobs that queued while we were disconnected
        jobs_processed += drain_jobs(client, translator)

        try:
            log.info("Connecting to SSE job events stream...")
            with client.job_events() as events:
                reconnect_delay = 1  # reset on successful connect
                log.info("SSE connected, waiting for events")
                for event in events:
                    if event.event == "job":
                        data = json.loads(event.data)
                        kind = data.get("kind", "")
                        if kind in JOB_KINDS:
                            log.info("SSE: job event for %s, draining queue", kind)
                            jobs_processed += drain_jobs(client, translator)
        except KeyboardInterrupt:
            log.info("Shutting down (processed %d jobs)", jobs_processed)
            raise
        except Exception as e:
            log.warning("SSE disconnected: %s — polling fallback for %ds", e, reconnect_delay)
            # Poll once before reconnecting
            jobs_processed += drain_jobs(client, translator)
            time.sleep(reconnect_delay)
            reconnect_delay = min(reconnect_delay * 2, poll_interval)


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    from .config import Config
    from .client import ProcessorClient
    from .translator import Translator

    cfg = Config()
    client = ProcessorClient(cfg.backend_url, cfg.processor_token)

    log.info("Loading translation models...")
    translator = Translator()

    log.info(
        "Translate worker starting (backend=%s, poll=%ds)",
        cfg.backend_url,
        cfg.poll_interval,
    )

    wait_for_backend(client)

    try:
        run_sse_loop(client, translator, cfg.poll_interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
