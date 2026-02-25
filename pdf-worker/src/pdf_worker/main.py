"""PDF worker main loop.

Claims build_searchable_pdf jobs from the backend, builds searchable PDFs
(with invisible text layer over scanned page images), and uploads them back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import io
import logging

from PIL import Image
from worker_common import run_sse_loop, wait_for_backend

log = logging.getLogger(__name__)

JOB_KIND = "build_searchable_pdf"


def process_one(client, job: dict) -> None:
    """Process a single searchable-PDF job."""
    from .builder import build_searchable_pdf

    job_id = job["id"]
    record_id = job["recordId"]

    log.info("Processing job %d: record %d", job_id, record_id)

    pages_meta = client.get_record_pages(record_id)
    log.info("  Record %d has %d pages", record_id, len(pages_meta))

    if not pages_meta:
        raise RuntimeError(f"Record {record_id} has no pages")

    pages = []
    for pm in pages_meta:
        page_id = pm["page_id"]
        seq = pm.get("seq", 0)
        text = pm.get("text_raw", "") or ""

        log.info("  Downloading image for page %d (seq=%d)", page_id, seq)
        image_bytes = client.download_page_image(page_id)
        log.info("    Downloaded %d bytes", len(image_bytes))

        img = Image.open(io.BytesIO(image_bytes))
        width, height = img.size

        pages.append({
            "image": image_bytes,
            "text": text,
            "width": width,
            "height": height,
            "seq": seq,
        })

    log.info("  Building searchable PDF for record %d (%d pages)", record_id, len(pages))
    pdf_bytes = build_searchable_pdf(pages)
    log.info("  PDF built: %d bytes", len(pdf_bytes))

    log.info("  Uploading searchable PDF for record %d", record_id)
    client.upload_searchable_pdf(record_id, pdf_bytes)
    log.info("  Upload complete")

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
        "PDF worker starting (backend=%s, poll=%ds)",
        cfg.backend_url,
        cfg.poll_interval,
    )

    wait_for_backend(client, JOB_KIND)

    try:
        run_sse_loop(
            client,
            job_kinds=[JOB_KIND],
            process_fn=lambda job: process_one(client, job),
            poll_interval=cfg.poll_interval,
        )
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
