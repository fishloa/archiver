"""CLI entry point for scraper-oesta.

Enumerates digitised records from archivinformationssystem.at (Austrian State Archives),
downloads page images, builds PDFs, and POSTs everything to the backend API.
"""

import argparse
import logging
import sys
import time
import uuid
from io import BytesIO

from PIL import Image

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM
from worker_common.http import wait_for_backend
from .session import OeStASession
from .parser import parse_detail_page
from .pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Austrian State Archives"


def ingest_record(
    client: BackendClient,
    session: OeStASession,
    record_id: str,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single record: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: OeStA session.
        record_id: Record ID from archivinformationssystem.at.
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    label = f"ID={record_id}"

    # Check existing status from pre-fetched map
    if not dry_run:
        current_status = known_statuses.get(record_id)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", label, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, record_id)
            backend_record_id = status_info.get("id")
            if backend_record_id:
                log.info(
                    "[CLEANUP] %s — deleting incomplete record %s",
                    label,
                    backend_record_id,
                )
                client.delete_record(backend_record_id)
                known_statuses.pop(record_id, None)

    # Load full record detail
    log.info("[START] %s", label)
    detail_html = session.get_detail(record_id)
    detail = parse_detail_page(detail_html, record_id)

    if dry_run:
        sig = detail.get("signature", "?")
        title = detail.get("title", "")
        objs = len(detail.get("digital_objects", []))
        log.info(
            "[DRY-RUN] %s — sig=%s, title=%s, %d digital objects",
            label,
            sig,
            title,
            objs,
        )
        return "ok"

    # Prepare metadata for backend
    metadata = {
        "referenceCode": detail.get("signature", ""),
        "title": detail.get("title", ""),
        "dateRangeText": detail.get("dateRangeText", ""),
        "description": detail.get("description", ""),
        "level": detail.get("level", ""),
        "extent": detail.get("extent", ""),
        "language": detail.get("language", ""),
    }
    # Remove empty values
    metadata = {k: v for k, v in metadata.items() if v}

    # Create record in backend
    create_metadata = dict(metadata)
    create_metadata["sourceUrl"] = (
        f"https://www.archivinformationssystem.at/detail.aspx?ID={record_id}"
    )
    backend_record_id = client.create_record(SOURCE_SYSTEM, record_id, create_metadata)

    # Get digital objects
    digital_objects = detail.get("digital_objects", [])

    if not digital_objects:
        log.warning("[SKIP] %s — no digital objects available", label)
        client.complete_ingest(backend_record_id)
        known_statuses[record_id] = "ocr_pending"
        return "ok"

    log.info("  Found %d digital object(s)", len(digital_objects))

    # Download and upload each page
    page_images = []
    for seq, obj in enumerate(digital_objects, start=1):
        veid = obj["veid"]
        deid = obj["deid"]
        sqnznr = obj["sqnznr"]
        klid = obj.get("klid")

        log.info(
            "  [%d/%d] Downloading image veid=%s deid=%s sqnznr=%s...",
            seq,
            len(digital_objects),
            veid,
            deid,
            sqnznr,
        )

        image_bytes = session.get_image(veid, deid, sqnznr, width=1200, klid=klid)
        if image_bytes is None:
            log.warning("  [%d/%d] Failed to download image", seq, len(digital_objects))
            continue

        try:
            # Convert image bytes to PIL Image
            img = Image.open(BytesIO(image_bytes))
            # Ensure RGB format for PDF compatibility
            if img.mode != "RGB":
                img = img.convert("RGB")
            page_images.append(img)

            # Convert to JPEG bytes for upload
            jpeg_buf = BytesIO()
            img.save(jpeg_buf, format="JPEG", quality=90)
            jpeg_bytes = jpeg_buf.getvalue()

            # Upload page to backend
            client.upload_page(
                backend_record_id,
                seq,
                jpeg_bytes,
                metadata={"veid": veid, "deid": deid, "sqnznr": sqnznr},
            )

            time.sleep(cfg.delay * 0.2)
        except Exception as e:
            log.warning(
                "  [%d/%d] Failed to process image: %s", seq, len(digital_objects), e
            )
            continue

    # Build and upload PDF
    if page_images:
        log.info("  Building PDF from %d pages...", len(page_images))
        try:
            pdf_bytes = build_pdf(page_images)
            client.upload_pdf(backend_record_id, pdf_bytes)
            log.info("  PDF uploaded (%d bytes)", len(pdf_bytes))
        except Exception as e:
            log.warning("  Failed to build/upload PDF: %s", e)

    # Try to download and upload report PDF
    try:
        log.info("  Downloading report PDF...")
        report_pdf = session.get_report_pdf(record_id)
        if report_pdf and len(report_pdf) > 1000:
            client.upload_pdf(backend_record_id, report_pdf)
            log.info("  Report PDF uploaded (%d bytes)", len(report_pdf))
    except Exception as e:
        log.debug("  Failed to download report PDF: %s", e)

    # Mark complete
    client.complete_ingest(backend_record_id)
    known_statuses[record_id] = "ocr_pending"
    log.info("[DONE] %s — %d pages ingested", label, len(page_images))
    return "ok"


def run_scrape(
    records: list[str],
    session: OeStASession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Process a list of record IDs. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, record_id in enumerate(records, start=1):
        label = f"ID={record_id}"
        log.info("=== [%d/%d] %s ===", i, len(records), label)

        try:
            result = ingest_record(
                client, session, record_id, known_statuses, dry_run=dry_run
            )
            if result == "skipped":
                skipped += 1
            elif result == "ok":
                success += 1
            else:
                failed += 1
        except Exception as e:
            log.error("Failed to ingest %s: %s", label, e, exc_info=verbose)
            failed += 1

        if client and scraper_id:
            client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME, success)

        time.sleep(get_config().delay)

    return success, failed, skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-oesta",
        description="Scrape digitised records from Austrian State Archives (archivinformationssystem.at)",
    )
    parser.add_argument(
        "action",
        nargs="?",
        help="Action to perform: 'search' or 'detail'",
    )
    parser.add_argument(
        "term_or_id",
        nargs="?",
        help="Search term (for 'search' action) or record ID (for 'detail' action)",
    )
    parser.add_argument(
        "--backend-url",
        help="Backend API base URL (overrides BACKEND_URL env var)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        help="Delay between requests in seconds (overrides SCRAPER_DELAY env var)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Enumerate and log records but do not upload anything",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable debug logging",
    )

    args = parser.parse_args()

    # Configure logging
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    if not args.action or not args.term_or_id:
        parser.error(
            "Provide an action ('search' or 'detail') and a search term or record ID"
        )

    # Apply config overrides
    cfg = Config()
    if args.backend_url:
        cfg.backend_url = args.backend_url
    if args.delay is not None:
        cfg.delay = args.delay
    set_config(cfg)

    # Pre-fetch all known statuses from the backend for resume support
    known_statuses: dict[str, str] = {}
    client = None
    if not args.dry_run:
        wait_for_backend(cfg.require_backend())
        client = BackendClient()
        log.info("Fetching existing record statuses from backend...")
        known_statuses = client.get_all_statuses(SOURCE_SYSTEM)
        already_done = sum(1 for s in known_statuses.values() if s != "ingesting")
        incomplete = sum(1 for s in known_statuses.values() if s == "ingesting")
        log.info(
            "Backend has %d records (%d complete, %d incomplete)",
            len(known_statuses),
            already_done,
            incomplete,
        )

    scraper_id = uuid.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    session = OeStASession()
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        if args.action.lower() == "search":
            # Search for records
            log.info("Searching archivinformationssystem.at for: '%s'", args.term_or_id)
            record_ids = session.search(args.term_or_id)
            log.info("Found %d records", len(record_ids))

            if not record_ids:
                log.warning("No records found.")
                sys.exit(0)

            s, f, sk = run_scrape(
                record_ids, session, client, known_statuses, args.dry_run, args.verbose,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

        elif args.action.lower() == "detail":
            # Ingest a specific record by ID
            record_id = args.term_or_id
            log.info("Ingesting record %s", record_id)
            s, f, sk = run_scrape(
                [record_id], session, client, known_statuses, args.dry_run, args.verbose,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

        else:
            parser.error(f"Unknown action: {args.action}. Use 'search' or 'detail'.")

    except KeyboardInterrupt:
        log.warning("Interrupted by user")
    finally:
        session.close()
        if client:
            client.close()

    log.info(
        "Finished: %d success, %d failed, %d skipped",
        total_success,
        total_failed,
        total_skipped,
    )


if __name__ == "__main__":
    main()
