"""OCR worker main loop.

Claims ocr_page_paddle jobs from the backend, runs PaddleOCR on
each page image, and posts the results back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import json
import logging

from worker_common import run_sse_loop, wait_for_backend

log = logging.getLogger(__name__)

JOB_KIND = "ocr_page_paddle"
ENGINE_NAME = "ocr_page_paddle"


def job_lang(job: dict, default: str) -> str:
    """Extract language from job payload, falling back to default.

    Accepts both ISO 639-1 codes (de, cs) and PaddleOCR names (german, czech).
    Always returns a PaddleOCR language name.
    """
    from .config import ISO_TO_PADDLE

    payload = job.get("payload")
    if payload:
        try:
            data = json.loads(payload) if isinstance(payload, str) else payload
            lang = data.get("lang")
            if lang:
                return ISO_TO_PADDLE.get(lang.lower(), lang)
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

    wait_for_backend(client, JOB_KIND)

    try:
        run_sse_loop(
            client,
            job_kinds=[JOB_KIND],
            process_fn=lambda job: process_one(client, job, cfg.ocr_lang, use_gpu),
            poll_interval=cfg.poll_interval,
        )
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
