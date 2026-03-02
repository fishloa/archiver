"""CLI entry point for scraper-matricula.

Scrapes digitised church records from Matricula Online (data.matricula-online.eu),
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
from .client import BackendClient, SOURCE_SYSTEM, wait_for_backend
from .session import MatriculaSession
from .pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Matricula Online"


def image_to_jpeg_bytes(img: Image.Image) -> bytes:
    """Convert a PIL Image to JPEG bytes."""
    buf = BytesIO()
    if img.mode in ("RGBA", "LA", "P"):
        # Convert transparent/palette images to RGB
        rgb_img = Image.new("RGB", img.size, (255, 255, 255))
        rgb_img.paste(img, mask=img.split()[-1] if img.mode in ("RGBA", "LA") else None)
        rgb_img.save(buf, format="JPEG", quality=90)
    else:
        img.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def ingest_register(
    client: BackendClient,
    session: MatriculaSession,
    register_info: dict,
    parish_name: str,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single register: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: Matricula session.
        register_info: Register dict with 'name', 'url', 'date_range'.
        parish_name: Name of the parish containing this register.
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    register_name = register_info.get("name", "Unknown")
    register_url = register_info.get("url", "")
    date_range = register_info.get("date_range", "")
    source_record_id = register_url  # Use URL as unique ID

    # Check existing status
    if not dry_run:
        current_status = known_statuses.get(source_record_id)
        if current_status and current_status not in ("ingesting",):
            log.info(
                "[SKIP] %s / %s — already %s",
                parish_name,
                register_name,
                current_status,
            )
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, source_record_id)
            record_id = status_info.get("id")
            if record_id:
                log.info(
                    "[CLEANUP] %s / %s — deleting incomplete record %s",
                    parish_name,
                    register_name,
                    record_id,
                )
                client.delete_record(record_id)
                known_statuses.pop(source_record_id, None)

    label = f"{parish_name} / {register_name}"
    log.info("[START] %s (URL: %s)", label, register_url)

    # Get all pages from the JS config (single HTTP request)
    try:
        pages = session.get_register_pages(register_url)
        page_count = len(pages)
        log.info("  Register has %d pages", page_count)
    except Exception as e:
        log.error("Failed to get register pages: %s", e)
        return "failed"

    if page_count == 0:
        log.warning("  No pages found for register")
        return "failed"

    if dry_run:
        log.info("[DRY-RUN] %s — %d pages", label, page_count)
        return "ok"

    # Create record metadata
    metadata = {
        "title": register_name,
        "description": f"Church register from {parish_name}",
        "dateRangeText": date_range,
        "referenceCode": register_url.split("/")[-2]
        if register_url.endswith("/")
        else register_url.split("/")[-1],
        "sourceUrl": register_url,
        "archive_id": 5,
    }

    try:
        record_id = client.create_record(SOURCE_SYSTEM, source_record_id, metadata)
    except Exception as e:
        log.error("Failed to create record: %s", e)
        return "failed"

    # Download and upload pages
    page_images = []
    for page_num, page_info in enumerate(pages, start=1):
        try:
            image_url = page_info["image_url"]
            page_label = page_info["label"]

            log.info("  [%d/%d] %s — downloading...", page_num, page_count, page_label)
            image_bytes = session.download_image(image_url)

            # Convert to PIL Image
            img = Image.open(BytesIO(image_bytes))
            if img.format != "JPEG":
                img = img.convert("RGB")
            page_images.append(img)

            # Upload page
            jpeg_bytes = image_to_jpeg_bytes(img)
            client.upload_page(
                record_id,
                page_num,
                jpeg_bytes,
                metadata={
                    "pageLabel": page_label,
                    "width": img.width,
                    "height": img.height,
                },
            )

            time.sleep(cfg.delay * 0.2)

        except Exception as e:
            log.warning(
                "  [%d/%d] Failed to download/upload page: %s", page_num, page_count, e
            )
            continue

    # Build and upload PDF
    if page_images:
        try:
            log.info("  Building PDF from %d pages...", len(page_images))
            pdf_bytes = build_pdf(page_images)
            client.upload_pdf(record_id, pdf_bytes)
            log.info("  PDF uploaded (%d bytes)", len(pdf_bytes))
        except Exception as e:
            log.error("Failed to build/upload PDF: %s", e)

    # Mark complete
    try:
        client.complete_ingest(record_id)
        known_statuses[source_record_id] = "ocr_pending"
    except Exception as e:
        log.error("Failed to complete ingest: %s", e)
        return "failed"

    log.info("[DONE] %s — %d pages ingested", label, len(page_images))
    return "ok"


def ingest_parish(
    client: BackendClient,
    session: MatriculaSession,
    parish_url: str,
    parish_name: str,
    known_statuses: dict[str, str],
    dry_run: bool = False,
    max_registers: int | None = None,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Ingest all registers in a parish. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    log.info("Listing registers for parish: %s", parish_name)
    try:
        page_parish_name, registers = session.list_registers(parish_url)
        if page_parish_name and parish_name == "Unnamed Parish":
            parish_name = page_parish_name
    except Exception as e:
        log.error("Failed to list registers: %s", e)
        return 0, 1, 0

    log.info("Found %d registers for %s", len(registers), parish_name)
    if max_registers:
        registers = registers[:max_registers]

    for i, register_info in enumerate(registers, start=1):
        log.info(
            "=== [%d/%d] %s ===",
            i,
            len(registers),
            register_info.get("name", "Unknown"),
        )
        try:
            result = ingest_register(
                client,
                session,
                register_info,
                parish_name,
                known_statuses,
                dry_run=dry_run,
            )
            if result == "skipped":
                skipped += 1
            elif result == "ok":
                success += 1
            else:
                failed += 1
        except Exception as e:
            log.error("Failed to ingest register: %s", e, exc_info=True)
            failed += 1

        if client and scraper_id:
            client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME, success)

        time.sleep(get_config().delay)

    return success, failed, skipped


def ingest_diocese(
    client: BackendClient,
    session: MatriculaSession,
    diocese_url: str,
    known_statuses: dict[str, str],
    dry_run: bool = False,
    max_parishes: int | None = None,
    max_registers_per_parish: int | None = None,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Ingest all parishes in a diocese. Returns (success, failed, skipped)."""
    total_success, total_failed, total_skipped = 0, 0, 0

    log.info("Listing parishes in diocese...")
    try:
        parishes = session.list_parishes(diocese_url)
    except Exception as e:
        log.error("Failed to list parishes: %s", e)
        return 0, 1, 0

    log.info("Found %d parishes", len(parishes))
    if max_parishes:
        parishes = parishes[:max_parishes]

    for i, (parish_name, parish_url) in enumerate(parishes, start=1):
        log.info(
            "\n============================================================\n"
            "  [%d/%d] Parish: %s\n"
            "============================================================",
            i,
            len(parishes),
            parish_name,
        )

        s, f, sk = ingest_parish(
            client,
            session,
            parish_url,
            parish_name,
            known_statuses,
            dry_run=dry_run,
            max_registers=max_registers_per_parish,
            scraper_id=scraper_id,
        )
        total_success += s
        total_failed += f
        total_skipped += sk

    return total_success, total_failed, total_skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-matricula",
        description="Scrape digitised church records from Matricula Online",
    )
    parser.add_argument(
        "command",
        nargs="?",
        choices=["parish", "register", "diocese"],
        help="Command: 'parish' (all registers in a parish), "
        "'register' (single register), 'diocese' (all parishes in diocese)",
    )
    parser.add_argument(
        "url",
        nargs="?",
        help="URL to scrape (parish, register, or diocese URL)",
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
        "--max-items",
        type=int,
        help="Maximum number of items (parishes or registers) to process",
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

    if not args.command or not args.url:
        parser.error("Provide a command (parish|register|diocese) and a URL")

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
        try:
            known_statuses = client.get_all_statuses(SOURCE_SYSTEM)
            already_done = sum(1 for s in known_statuses.values() if s != "ingesting")
            incomplete = sum(1 for s in known_statuses.values() if s == "ingesting")
            log.info(
                "Backend has %d records (%d complete, %d incomplete)",
                len(known_statuses),
                already_done,
                incomplete,
            )
        except Exception as e:
            log.warning("Could not fetch statuses: %s", e)

    scraper_id = uuid.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    session = MatriculaSession()
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        if args.command == "parish":
            log.info("Scraping parish: %s", args.url)
            s, f, sk = ingest_parish(
                client,
                session,
                args.url,
                "Unnamed Parish",
                known_statuses,
                dry_run=args.dry_run,
                max_registers=args.max_items,
                scraper_id=scraper_id,
            )
            total_success, total_failed, total_skipped = s, f, sk

        elif args.command == "register":
            log.info("Scraping register: %s", args.url)
            register_info = {"name": "Register", "url": args.url, "date_range": ""}
            result = ingest_register(
                client,
                session,
                register_info,
                "Parish",
                known_statuses,
                dry_run=args.dry_run,
            )
            if result == "ok":
                total_success = 1
            elif result == "skipped":
                total_skipped = 1
            else:
                total_failed = 1

        elif args.command == "diocese":
            log.info("Scraping diocese: %s", args.url)
            s, f, sk = ingest_diocese(
                client,
                session,
                args.url,
                known_statuses,
                dry_run=args.dry_run,
                max_parishes=args.max_items,
                max_registers_per_parish=None,
                scraper_id=scraper_id,
            )
            total_success, total_failed, total_skipped = s, f, sk

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

    sys.exit(0 if total_failed == 0 else 1)


if __name__ == "__main__":
    main()
