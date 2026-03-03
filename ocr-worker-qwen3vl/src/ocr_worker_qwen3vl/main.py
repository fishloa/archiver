"""Qwen VLM OCR worker main loop.

Claims ocr_page_qwen3vl jobs from the backend, runs Qwen VLM on each
page image via MLX (Apple Silicon Metal), and posts the results back.
"""

import json
import logging

from worker_common import run_sse_loop, wait_for_backend

log = logging.getLogger(__name__)

JOB_KIND = "ocr_page_qwen3vl"
ENGINE_NAME = "ocr_page_qwen3vl"


def process_one(client, job: dict, model_name: str) -> None:
    """Process a single OCR job."""
    from .ocr import process_image

    job_id = job["id"]
    page_id = job["pageId"]
    record_id = job.get("recordId", "?")

    log.info("Processing job %d: page %d (record %s)", job_id, page_id, record_id)

    image_bytes = client.download_page_image(page_id)
    log.info("  Downloaded %d bytes", len(image_bytes))

    result = process_image(image_bytes, model_name=model_name)
    log.info(
        "  OCR: %.2f confidence, %d chars",
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

    log.info(
        "Qwen VLM OCR worker starting (backend=%s, model=%s, poll=%ds)",
        cfg.backend_url,
        cfg.model_name,
        cfg.poll_interval,
    )

    wait_for_backend(client, JOB_KIND)

    try:
        run_sse_loop(
            client,
            job_kinds=[JOB_KIND],
            process_fn=lambda job: process_one(client, job, cfg.model_name),
            poll_interval=cfg.poll_interval,
        )
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
