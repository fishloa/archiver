"""PDF worker main loop.

Claims build_searchable_pdf jobs from the backend, builds searchable PDFs
(with invisible text layer over scanned page images), and uploads them back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import json
import logging
import time

from PIL import Image
import io

log = logging.getLogger(__name__)

JOB_KIND = "build_searchable_pdf"


def process_one(client, job: dict) -> None:
    """Process a single searchable-PDF job."""
    from .builder import build_searchable_pdf

    job_id = job["id"]
    record_id = job["recordId"]

    log.info("Processing job %d: record %d", job_id, record_id)

    # Fetch pages with OCR text
    pages_meta = client.get_record_pages(record_id)
    log.info("  Record %d has %d pages", record_id, len(pages_meta))

    if not pages_meta:
        raise RuntimeError(f"Record {record_id} has no pages")

    # Download all page images and build page data
    pages = []
    for pm in pages_meta:
        page_id = pm["page_id"]
        seq = pm.get("seq", 0)
        text = pm.get("text_raw", "") or ""

        log.info("  Downloading image for page %d (seq=%d)", page_id, seq)
        image_bytes = client.download_page_image(page_id)
        log.info("    Downloaded %d bytes", len(image_bytes))

        # Get image dimensions
        img = Image.open(io.BytesIO(image_bytes))
        width, height = img.size

        pages.append({
            "image": image_bytes,
            "text": text,
            "width": width,
            "height": height,
            "seq": seq,
        })

    # Build the searchable PDF
    log.info("  Building searchable PDF for record %d (%d pages)", record_id, len(pages))
    pdf_bytes = build_searchable_pdf(pages)
    log.info("  PDF built: %d bytes", len(pdf_bytes))

    # Upload the PDF
    log.info("  Uploading searchable PDF for record %d", record_id)
    client.upload_searchable_pdf(record_id, pdf_bytes)
    log.info("  Upload complete")

    client.complete_job(job_id)
    log.info("  Job %d completed", job_id)


def drain_jobs(client) -> int:
    """Claim and process all available jobs. Returns number processed."""
    count = 0
    while True:
        job = client.claim_job(JOB_KIND)
        if job is None:
            return count
        try:
            process_one(client, job)
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


def run_sse_loop(client, poll_interval: int):
    """Main loop: subscribe to SSE, drain jobs on each event, reconnect on failure."""
    jobs_processed = 0
    reconnect_delay = 1

    while True:
        # Drain any jobs that queued while we were disconnected
        jobs_processed += drain_jobs(client)

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
                            jobs_processed += drain_jobs(client)
        except KeyboardInterrupt:
            log.info("Shutting down (processed %d jobs)", jobs_processed)
            raise
        except Exception as e:
            log.warning("SSE disconnected: %s — polling fallback for %ds", e, reconnect_delay)
            # Poll once before reconnecting
            jobs_processed += drain_jobs(client)
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

    log.info(
        "PDF worker starting (backend=%s, poll=%ds)",
        cfg.backend_url,
        cfg.poll_interval,
    )

    wait_for_backend(client)

    try:
        run_sse_loop(client, cfg.poll_interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
