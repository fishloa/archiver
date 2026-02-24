"""CLI entry point for scraper-cz.

Enumerates digitised records from the Czech National Archives VadeMeCum portal,
downloads page images (Zoomify tiles), builds PDFs, and POSTs everything to
the backend API.
"""

import argparse
import json
import logging
import sys
import time

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM
from .session import VadeMeCumSession
from .enumerator import enumerate_all
from .record import (
    load_record_detail,
    collect_all_scan_uuids,
)
from .zoomify import download_and_stitch, image_to_jpeg_bytes
from .pdf import build_pdf

log = logging.getLogger(__name__)


def ingest_record(
    client: BackendClient,
    session: VadeMeCumSession,
    rec: dict,
    dry_run: bool = False,
) -> bool:
    """Ingest a single record: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: VadeMeCum session.
        rec: Record dict from enumeration (must have xid, and optionally
             inv, sig, scans, etc.).
        dry_run: If True, skip actual uploads.

    Returns:
        True if successful.
    """
    cfg = get_config()
    xid = rec["xid"]
    label = f"inv.{rec.get('inv', '?')} sig.{rec.get('sig', '?')}"

    # Check existing status
    if not dry_run:
        status_info = client.get_status(SOURCE_SYSTEM, xid)
        current_status = status_info.get("status")
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — status=%s", label, current_status)
            return True
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            record_id = status_info.get("id")
            if record_id:
                log.info("[CLEANUP] %s — deleting incomplete record %s", label, record_id)
                client.delete_record(record_id)

    # Load full record detail
    log.info("[START] %s (xid=%s)", label, xid)
    detail = load_record_detail(session, xid)

    if dry_run:
        log.info("[DRY-RUN] %s — %d scans, detail: %s",
                 label, detail.get("scans", 0), detail.get("title", ""))
        return True

    # Create record in backend
    metadata = {
        k: v for k, v in detail.items()
        if k != "entity_ref" and v
    }
    record_id = client.create_record(SOURCE_SYSTEM, xid, metadata)

    # Get scan UUIDs
    entity_ref = detail.get("entity_ref")
    scan_count = detail.get("scans", 0)

    if not entity_ref or scan_count == 0:
        log.warning("[SKIP] %s — no scans available", label)
        client.complete_ingest(record_id)
        return True

    # Load Zoomify viewer page to get scan UUIDs
    zoom_html = session.get_zoomify_page(entity_ref, scan_index=0)
    all_uuids = collect_all_scan_uuids(session, entity_ref, scan_count, zoom_html)
    log.info("  Collected %d/%d scan UUIDs", len(all_uuids), scan_count)

    # Download and upload each page
    page_images = []
    for seq, (folder, uuid) in enumerate(all_uuids, start=1):
        log.info("  [%d/%d] Downloading tiles for %s/%s...",
                 seq, len(all_uuids), folder, uuid[:8])

        img = download_and_stitch(folder, uuid, session=session)
        if img is None:
            log.warning("  [%d/%d] Failed to stitch image", seq, len(all_uuids))
            continue

        page_images.append(img)

        # Upload page to backend
        jpeg_bytes = image_to_jpeg_bytes(img)
        client.upload_page(
            record_id, seq, jpeg_bytes,
            metadata={"folder": folder, "uuid": uuid},
        )

        time.sleep(cfg.delay * 0.2)

    # Build and upload PDF
    if page_images:
        log.info("  Building PDF from %d pages...", len(page_images))
        pdf_bytes = build_pdf(page_images)
        client.upload_pdf(record_id, pdf_bytes)
        log.info("  PDF uploaded (%d bytes)", len(pdf_bytes))

    # Mark complete
    client.complete_ingest(record_id)
    log.info("[DONE] %s — %d pages ingested", label, len(page_images))
    return True


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-cz",
        description="Scrape digitised records from Czech National Archives VadeMeCum portal",
    )
    parser.add_argument(
        "term",
        nargs="?",
        help="Search term for VadeMeCum fulltext search",
    )
    parser.add_argument(
        "--all",
        metavar="FILE",
        help="Load records from a JSON file (e.g. digi_items.json) instead of searching",
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
        "--digi-only",
        action="store_true",
        default=True,
        help="Only include digitised records (default: True)",
    )
    parser.add_argument(
        "--nad",
        type=int,
        help="Filter by NAD number",
    )
    parser.add_argument(
        "--levels",
        nargs="+",
        default=None,
        help="Search levels (9019=item, 10026=inventory, 10060=fond)",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=5000,
        help="Maximum number of items to enumerate (default: 5000)",
    )
    parser.add_argument(
        "-v", "--verbose",
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

    if not args.term and not args.all:
        parser.error("Provide a search term or --all FILE")

    # Apply config overrides
    cfg = Config()
    if args.backend_url:
        cfg.backend_url = args.backend_url
    if args.delay is not None:
        cfg.delay = args.delay
    set_config(cfg)

    # Load or enumerate records
    if args.all:
        log.info("Loading records from %s", args.all)
        with open(args.all) as f:
            records = json.load(f)
        log.info("Loaded %d records", len(records))
    else:
        log.info("Searching VadeMeCum for: '%s'", args.term)
        session = VadeMeCumSession()
        total, records = enumerate_all(
            session, args.term,
            digi_only=args.digi_only,
            levels=args.levels,
            nad=args.nad,
            max_items=args.max_items,
        )
        log.info("Enumerated %d/%d records", len(records), total)

    if not records:
        log.warning("No records found.")
        sys.exit(0)

    total_scans = sum(r.get("scans", 0) for r in records)
    log.info(
        "Processing %d records (%d total scans, ~%.0f MB estimated)",
        len(records), total_scans, total_scans * 250 / 1024,
    )

    # Set up session and client
    if not args.all:
        # Reuse existing session
        pass
    else:
        session = VadeMeCumSession()

    client = None
    if not args.dry_run:
        client = BackendClient()

    # Process records
    success, failed, skipped = 0, 0, 0

    try:
        for i, rec in enumerate(records, start=1):
            label = f"inv.{rec.get('inv', '?')} sig.{rec.get('sig', '?')}"
            log.info(
                "=== [%d/%d] %s (%d scans) ===",
                i, len(records), label, rec.get("scans", 0),
            )

            try:
                ok = ingest_record(client, session, rec, dry_run=args.dry_run)
                if ok:
                    success += 1
                else:
                    failed += 1
            except Exception as e:
                log.error("Failed to ingest %s: %s", label, e, exc_info=args.verbose)
                failed += 1

            time.sleep(get_config().delay)

    except KeyboardInterrupt:
        log.warning("Interrupted by user")
    finally:
        if client:
            client.close()

    log.info(
        "Finished: %d success, %d failed, %d skipped out of %d total",
        success, failed, skipped, len(records),
    )


if __name__ == "__main__":
    main()
