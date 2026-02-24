"""OCR worker main loop.

Claims ocr_page_paddle jobs from the backend, runs PaddleOCR on
each page image, and posts the results back.

Uses PostgreSQL LISTEN/NOTIFY for real-time job pickup when a
DB connection URL is provided, otherwise falls back to polling.
"""

import json
import logging
import select
import sys
import time

log = logging.getLogger(__name__)

JOB_KIND = "ocr_page_paddle"
ENGINE_NAME = "ocr_page_paddle"


def listen_pg(db_url: str):
    """Connect to PostgreSQL and LISTEN on ocr_jobs channel.

    Yields each time a notification arrives.
    Falls back to periodic polling every poll_interval seconds.
    """
    import psycopg2

    conn = psycopg2.connect(db_url)
    conn.set_isolation_level(0)  # autocommit
    with conn.cursor() as cur:
        cur.execute("LISTEN ocr_jobs;")
    log.info("Listening on PostgreSQL channel 'ocr_jobs'")

    try:
        while True:
            if select.select([conn], [], [], 30.0) == ([], [], []):
                # Timeout — poll anyway in case we missed a notify
                yield
            else:
                conn.poll()
                while conn.notifies:
                    conn.notifies.pop(0)
                    yield
    finally:
        conn.close()


def poll_forever(interval: int):
    """Simple polling fallback when no DB URL is configured."""
    while True:
        yield
        time.sleep(interval)


def job_lang(job: dict, default: str) -> str:
    """Extract language from job payload, falling back to default."""
    payload = job.get("payload")
    if payload:
        try:
            data = json.loads(payload) if isinstance(payload, str) else payload
            lang = data.get("lang")
            if lang:
                return lang
        except (json.JSONDecodeError, AttributeError):
            pass
    return default


def process_one(client, job: dict, default_lang: str, use_gpu: bool) -> None:
    """Process a single OCR job."""
    from .ocr import process_image

    job_id = job["id"]
    page_id = job["pageId"]
    record_id = job.get("recordId", "?")
    lang = job_lang(job, default_lang)

    log.info("Processing job %d: page %d (record %s, lang=%s)", job_id, page_id, record_id, lang)

    # Download the page image
    image_bytes = client.download_page_image(page_id)
    log.info("  Downloaded %d bytes", len(image_bytes))

    # Run OCR
    result = process_image(image_bytes, lang=lang, use_gpu=use_gpu)
    log.info(
        "  OCR: %d regions, %.2f confidence, %d chars",
        result["regions"],
        result["confidence"],
        len(result["text"]),
    )

    # Submit results
    client.submit_ocr_result(
        page_id,
        engine=ENGINE_NAME,
        confidence=result["confidence"],
        text_raw=result["text"],
    )

    # Mark job complete
    client.complete_job(job_id)
    log.info("  Job %d completed", job_id)


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    from .config import Config
    from .client import ProcessorClient

    cfg = Config()
    client = ProcessorClient(cfg.backend_url, cfg.processor_token)

    # Detect GPU availability
    use_gpu = True
    try:
        import paddle

        use_gpu = paddle.device.is_compiled_with_cuda()
        log.info("PaddlePaddle GPU available: %s", use_gpu)
    except Exception:
        log.warning("Could not detect GPU, defaulting to GPU=True (will fail if no GPU)")

    log.info(
        "OCR worker starting (backend=%s, lang=%s, gpu=%s)",
        cfg.backend_url,
        cfg.ocr_lang,
        use_gpu,
    )

    # Choose notification source
    if cfg.db_notify_url:
        event_source = listen_pg(cfg.db_notify_url)
    else:
        log.info("No DB_NOTIFY_URL — falling back to polling every %ds", cfg.poll_interval)
        event_source = poll_forever(cfg.poll_interval)

    # Drain any pending jobs on startup, then wait for notifications
    jobs_processed = 0
    try:
        # First pass: drain queue
        while True:
            job = client.claim_job(JOB_KIND)
            if job is None:
                break
            try:
                process_one(client, job, cfg.ocr_lang, use_gpu)
                jobs_processed += 1
            except Exception as e:
                log.error("Job %d failed: %s", job["id"], e, exc_info=True)
                try:
                    client.fail_job(job["id"], str(e)[:500])
                except Exception:
                    log.error("Failed to report job failure", exc_info=True)

        log.info("Initial drain complete: %d jobs processed", jobs_processed)

        # Main loop: wait for notifications, then drain
        for _ in event_source:
            while True:
                job = client.claim_job(JOB_KIND)
                if job is None:
                    break
                try:
                    process_one(client, job, cfg.ocr_lang, use_gpu)
                    jobs_processed += 1
                except Exception as e:
                    log.error("Job %d failed: %s", job["id"], e, exc_info=True)
                    try:
                        client.fail_job(job["id"], str(e)[:500])
                    except Exception:
                        log.error("Failed to report job failure", exc_info=True)

    except KeyboardInterrupt:
        log.info("Shutting down (processed %d jobs)", jobs_processed)
    finally:
        client.close()


if __name__ == "__main__":
    main()
