"""OCR worker main loop.

Claims ocr_page_paddle jobs from the backend, runs PaddleOCR on
each page image, and posts the results back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import json
import logging
import time

log = logging.getLogger(__name__)

JOB_KIND = "ocr_page_paddle"
ENGINE_NAME = "ocr_page_paddle"


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

    image_bytes = client.download_page_image(page_id)
    log.info("  Downloaded %d bytes", len(image_bytes))

    result = process_image(image_bytes, lang=lang, use_gpu=use_gpu)
    log.info(
        "  OCR: %d regions, %.2f confidence, %d chars",
        result["regions"],
        result["confidence"],
        len(result["text"]),
    )

    client.submit_ocr_result(
        page_id,
        engine=ENGINE_NAME,
        confidence=result["confidence"],
        text_raw=result["text"],
    )

    client.complete_job(job_id)
    log.info("  Job %d completed", job_id)


def drain_jobs(client, default_lang: str, use_gpu: bool) -> int:
    """Claim and process all available jobs. Returns number processed."""
    count = 0
    while True:
        job = client.claim_job(JOB_KIND)
        if job is None:
            return count
        try:
            process_one(client, job, default_lang, use_gpu)
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
            client.claim_job(JOB_KIND)
            log.info("Backend reachable")
            return
        except Exception:
            log.info("Waiting for backend (%d/%d)...", attempt, max_retries)
            time.sleep(delay)
    raise RuntimeError(f"Backend not reachable after {max_retries * delay}s")


def run_sse_loop(client, default_lang: str, use_gpu: bool, poll_interval: int):
    """Main loop: subscribe to SSE, drain jobs on each event, reconnect on failure."""
    jobs_processed = 0
    reconnect_delay = 1

    while True:
        # Drain any jobs that queued while we were disconnected
        jobs_processed += drain_jobs(client, default_lang, use_gpu)

        try:
            log.info("Connecting to SSE job events stream...")
            with client.job_events() as events:
                reconnect_delay = 1  # reset on successful connect
                log.info("SSE connected, waiting for events")
                for event in events:
                    if event.event == "job":
                        data = json.loads(event.data)
                        kind = data.get("kind", "")
                        if kind == JOB_KIND:
                            log.info("SSE: job event for %s, draining queue", kind)
                            jobs_processed += drain_jobs(client, default_lang, use_gpu)
        except KeyboardInterrupt:
            log.info("Shutting down (processed %d jobs)", jobs_processed)
            raise
        except Exception as e:
            log.warning("SSE disconnected: %s — polling fallback for %ds", e, reconnect_delay)
            # Poll once before reconnecting
            jobs_processed += drain_jobs(client, default_lang, use_gpu)
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

    cfg = Config()
    client = ProcessorClient(cfg.backend_url, cfg.processor_token)

    use_gpu = True
    try:
        import paddle

        use_gpu = paddle.device.is_compiled_with_cuda() and paddle.device.cuda.device_count() > 0
        log.info("PaddlePaddle GPU available: %s", use_gpu)
    except Exception:
        log.warning("Could not detect GPU, defaulting to GPU=True (will fail if no GPU)")

    log.info(
        "OCR worker starting (backend=%s, lang=%s, gpu=%s, poll=%ds)",
        cfg.backend_url,
        cfg.ocr_lang,
        use_gpu,
        cfg.poll_interval,
    )

    wait_for_backend(client)

    try:
        run_sse_loop(client, cfg.ocr_lang, use_gpu, cfg.poll_interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
