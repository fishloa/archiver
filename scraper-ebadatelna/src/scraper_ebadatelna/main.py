"""CLI entry point for scraper-ebadatelna.

Enumerates digitised records from ebadatelna.cz (Czech Archive of Security Forces),
downloads page images, builds PDFs, and POSTs everything to the backend API.
"""

import argparse
import io
import logging
import sys
import time
import uuid

from PIL import Image

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM
from worker_common.http import wait_for_backend
from .session import EBadatelnaSession
from .pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Czech Archive of Security Forces"


def image_to_jpeg_bytes(img: Image.Image, quality: int = 95) -> bytes:
    """Convert a PIL Image to JPEG bytes."""
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def ingest_record(
    client: BackendClient,
    session: EBadatelnaSession,
    item: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single record: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: ebadatelna.cz session.
        item: Item dict from API (must have Id, Leaf, FileCount, etc.).
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    doc_id = item["Id"]
    title = item.get("Name", "")
    label = f"{doc_id} - {title}"

    # Check if already ingested
    if not dry_run:
        current_status = known_statuses.get(doc_id)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", label, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, doc_id)
            record_id = status_info.get("id")
            if record_id:
                log.info(
                    "[CLEANUP] %s — deleting incomplete record %s", label, record_id
                )
                client.delete_record(record_id)
                known_statuses.pop(doc_id, None)

    # Skip if not a document (Leaf != 1)
    if item.get("Leaf") != 1:
        log.info("[SKIP] %s — not a document (Leaf=%s)", label, item.get("Leaf"))
        return "skipped"

    # Skip if no files
    file_count = item.get("FileCount", 0)
    if file_count == 0 or item.get("HasFiles") == 0:
        log.info("[SKIP] %s — no files available", label)
        return "skipped"

    log.info("[START] %s (id=%s, pages=%d)", label, doc_id, file_count)

    if dry_run:
        log.info("[DRY-RUN] %s — would ingest %d pages", label, file_count)
        return "ok"

    # Create record metadata from item dict
    metadata = {
        "title": item.get("Name", ""),
        "referenceCode": item.get("Signature", ""),
        "dateRangeText": item.get("Dating", ""),
        "sourceUrl": "https://ebadatelna.cz/",
        "archive_id": 2,  # Czech Archive of Security Forces
    }

    # Add all fields to rawSourceMetadata
    import json as jsonmod

    metadata["rawSourceMetadata"] = jsonmod.dumps(item, ensure_ascii=False)

    # Create record in backend
    record_id = client.create_record(SOURCE_SYSTEM, doc_id, metadata)

    # Download images via GetSignatureImages -> GetImage flow
    page_images = []
    if session.authenticated:
        try:
            # GetSignatureImages returns thumbnail list with page paths
            sig_data = session.get_signature_images(doc_id)
            thumbnail_list = sig_data.get("ThumbnailList", [])
            total_images = sig_data.get("TotalImages", 0)
            total_pages = sig_data.get("TotalPages", 1)

            log.info(
                "  GetSignatureImages: %d images across %d pages",
                total_images,
                total_pages,
            )

            # Collect all page paths from all pagination pages
            all_page_paths = list(thumbnail_list)
            for sig_page in range(2, total_pages + 1):
                sig_data_next = session.get_signature_images(doc_id, page=sig_page)
                all_page_paths.extend(sig_data_next.get("ThumbnailList", []))

            log.info("  Collected %d page paths", len(all_page_paths))

            for seq, page_path in enumerate(all_page_paths, start=1):
                log.info("  [%d/%d] Downloading...", seq, len(all_page_paths))
                try:
                    img_bytes = session.get_image(page_path)
                    if not img_bytes or len(img_bytes) < 100:
                        log.warning("  [%d/%d] Empty image", seq, len(all_page_paths))
                        continue

                    img = Image.open(io.BytesIO(img_bytes))
                    if img.mode != "RGB":
                        img = img.convert("RGB")

                    page_images.append(img)

                    jpeg_bytes = image_to_jpeg_bytes(img)
                    client.upload_page(
                        record_id,
                        seq,
                        jpeg_bytes,
                        metadata={"page_path": page_path},
                    )
                    time.sleep(cfg.delay * 0.2)
                except Exception as e:
                    log.warning("  [%d/%d] Failed: %s", seq, len(all_page_paths), e)
                    continue

        except Exception as e:
            log.warning("  GetSignatureImages failed (auth required?): %s", e)
    else:
        log.warning("  Not authenticated — skipping image download")

    # Build and upload PDF
    if page_images:
        log.info("  Building PDF from %d pages...", len(page_images))
        try:
            pdf_bytes = build_pdf(page_images)
            client.upload_pdf(record_id, pdf_bytes)
            log.info("  PDF uploaded (%d bytes)", len(pdf_bytes))
        except Exception as e:
            log.error("  Failed to build/upload PDF: %s", e)

    # Mark complete
    client.complete_ingest(record_id)
    known_statuses[doc_id] = "ocr_pending"
    log.info("[DONE] %s — %d pages ingested", label, len(page_images))
    return "ok"


def run_ingest(
    items: list[dict],
    session: EBadatelnaSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
    max_items: int | None = None,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Process a list of items. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    # Limit to max_items if specified
    if max_items:
        items = items[:max_items]

    for i, item in enumerate(items, start=1):
        doc_id = item.get("Id", "?")
        title = item.get("Name", "")
        file_count = item.get("FileCount", 0)
        log.info(
            "=== [%d/%d] %s (%d pages) ===",
            i,
            len(items),
            title or doc_id,
            file_count,
        )

        try:
            result = ingest_record(
                client, session, item, known_statuses, dry_run=dry_run
            )
            if result == "skipped":
                skipped += 1
            elif result == "ok":
                success += 1
            else:
                failed += 1
        except Exception as e:
            log.error("Failed to ingest %s: %s", doc_id, e, exc_info=verbose)
            failed += 1

        if client and scraper_id:
            client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME, success)

        time.sleep(get_config().delay)

    return success, failed, skipped


def browse_collection(
    session: EBadatelnaSession,
    collection_id: str,
    max_items: int | None = None,
) -> list[dict]:
    """Recursively browse a collection and return all leaf documents.

    Args:
        session: ebadatelna.cz session.
        collection_id: Collection node ID (e.g., "s1275").
        max_items: Maximum number of items to return.

    Returns:
        List of document items.
    """
    log.info("Browsing collection %s...", collection_id)
    all_docs = []
    visited = set()

    def traverse(parent_id=None, depth=0):
        if max_items and len(all_docs) >= max_items:
            return

        if parent_id and parent_id in visited:
            return
        if parent_id:
            visited.add(parent_id)

        skip = 0
        take = 50
        while True:
            try:
                items, total = session.item_read(
                    parent_id=parent_id, skip=skip, take=take
                )
            except Exception as e:
                log.warning("Failed to fetch items for parent %s: %s", parent_id, e)
                break

            if not items:
                break

            for item in items:
                if max_items and len(all_docs) >= max_items:
                    return

                item_id = item.get("Id")
                is_leaf = item.get("Leaf", 0)
                has_files = item.get("HasFiles", 0)

                log.debug(
                    "  %s%s (Leaf=%s, HasFiles=%s)",
                    "  " * depth,
                    item.get("Name", ""),
                    is_leaf,
                    has_files,
                )

                if is_leaf == 1 and has_files == 1:
                    # Leaf document with files
                    all_docs.append(item)
                elif is_leaf != 1:
                    # Container — recurse
                    traverse(item_id, depth + 1)

            skip += take
            if skip >= total:
                break

    # Start from root if parent_id not given, otherwise from collection_id
    traverse(parent_id=collection_id, depth=0)
    log.info("Browsed collection %s: found %d documents", collection_id, len(all_docs))
    return all_docs


def search_ocr(
    session: EBadatelnaSession,
    search_text: str,
    max_items: int | None = None,
) -> list[dict]:
    """Full-text OCR search.

    Args:
        session: ebadatelna.cz session.
        search_text: Search query.
        max_items: Maximum number of items to return.

    Returns:
        List of document items matching the search.
    """
    log.info("Searching for: %s", search_text)
    all_docs = []

    skip = 0
    take = 50
    while True:
        if max_items and len(all_docs) >= max_items:
            break

        try:
            items, total = session.ocr_search(search_text, skip=skip, take=take)
        except Exception as e:
            log.error("OCR search failed: %s", e)
            break

        if not items:
            break

        for item in items:
            if max_items and len(all_docs) >= max_items:
                break

            # OCR search returns documents directly (assumed Leaf=1)
            all_docs.append(item)

        skip += take
        if skip >= total:
            break

    log.info("OCR search for '%s': found %d documents", search_text, len(all_docs))
    return all_docs


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-ebadatelna",
        description="Scrape digitised records from Czech Archive of Security Forces (ebadatelna.cz)",
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # search <query>
    search_parser = subparsers.add_parser("search", help="OCR full-text search")
    search_parser.add_argument("query", help="Search query string")

    # browse <collection_id>
    browse_parser = subparsers.add_parser("browse", help="Browse a collection by ID")
    browse_parser.add_argument("collection_id", help="Collection ID (e.g., s1275)")

    # Common options
    for sp in [search_parser, browse_parser]:
        sp.add_argument(
            "--backend-url",
            help="Backend API base URL (overrides BACKEND_URL env var)",
        )
        sp.add_argument(
            "--delay",
            type=float,
            help="Delay between requests in seconds (overrides SCRAPER_DELAY env var)",
        )
        sp.add_argument(
            "--dry-run",
            action="store_true",
            help="Enumerate and log records but do not upload anything",
        )
        sp.add_argument(
            "--max-items",
            type=int,
            help="Maximum number of items to ingest",
        )
        sp.add_argument(
            "-v",
            "--verbose",
            action="store_true",
            help="Enable debug logging",
        )

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    # Configure logging
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
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
        try:
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
        except Exception as e:
            log.warning("Could not fetch existing statuses: %s", e)
            if not cfg.backend_url:
                log.warning("BACKEND_URL not set; proceeding in dry-run mode")
                args.dry_run = True

    scraper_id = uuid.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    session = EBadatelnaSession()
    session.login()  # attempts login if credentials set; continues without if not
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        if args.command == "search":
            log.info("Running OCR search for: %s", args.query)
            items = search_ocr(session, args.query, max_items=args.max_items)

            if not items:
                log.warning("No items found.")
                sys.exit(0)

            log.info("Processing %d items", len(items))
            s, f, sk = run_ingest(
                items,
                session,
                client,
                known_statuses,
                args.dry_run,
                args.verbose,
                max_items=args.max_items,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

        elif args.command == "browse":
            log.info("Browsing collection: %s", args.collection_id)
            items = browse_collection(
                session, args.collection_id, max_items=args.max_items
            )

            if not items:
                log.warning("No items found.")
                sys.exit(0)

            log.info("Processing %d items", len(items))
            s, f, sk = run_ingest(
                items,
                session,
                client,
                known_statuses,
                args.dry_run,
                args.verbose,
                max_items=args.max_items,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

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
