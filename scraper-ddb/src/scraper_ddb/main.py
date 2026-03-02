"""CLI entry point for scraper-ddb.

Searches the Deutsche Digitale Bibliothek / Archivportal-D via the public Solr
API, fetches IIIF manifests for all pages, downloads images, builds PDFs,
and POSTs everything to the backend API.
"""

import argparse
import logging
import sys
import time
import uuid
from io import BytesIO

from PIL import Image

from worker_common.http import wait_for_backend

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM
from .session import DDBSession
from .pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Deutsche Digitale Bibliothek"

DDB_ITEM_BASE = "https://www.deutsche-digitale-bibliothek.de/item"


def image_to_jpeg_bytes(img: Image.Image) -> bytes:
    """Convert a PIL Image to RGB JPEG bytes at quality 90."""
    buf = BytesIO()
    if img.mode in ("RGBA", "LA", "P"):
        rgb_img = Image.new("RGB", img.size, (255, 255, 255))
        rgb_img.paste(img, mask=img.split()[-1] if img.mode in ("RGBA", "LA") else None)
        rgb_img.save(buf, format="JPEG", quality=90)
    else:
        if img.mode != "RGB":
            img = img.convert("RGB")
        img.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def ingest_document(
    client: BackendClient,
    session: DDBSession,
    doc: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single DDB document: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: DDB API session.
        doc: Solr result doc dict (from 'response.docs').
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, log but skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    item_id = doc.get("id", "")
    title = doc.get("title", item_id)
    if isinstance(title, list):
        title = title[0] if title else item_id
    label = f"id={item_id}"

    # Check existing status from pre-fetched map
    if not dry_run:
        current_status = known_statuses.get(item_id)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", label, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, item_id)
            backend_record_id = status_info.get("id")
            if backend_record_id:
                log.info(
                    "[CLEANUP] %s — deleting incomplete record %s",
                    label,
                    backend_record_id,
                )
                client.delete_record(backend_record_id)
                known_statuses.pop(item_id, None)

    log.info("[START] %s — %s", label, title)

    # Fetch IIIF manifest to get all pages
    manifest = session.get_manifest(item_id)
    if manifest:
        pages = DDBSession.extract_pages_from_manifest(manifest)
    else:
        # Fall back to preview image if manifest unavailable
        preview_store = doc.get("preview_store", "")
        if isinstance(preview_store, list):
            preview_store = preview_store[0] if preview_store else ""
        preview_url = DDBSession.preview_image_url(preview_store)
        pages = [{"label": "page_1", "image_url": preview_url}] if preview_url else []

    page_count = len(pages)
    log.info("  %d page(s) found", page_count)

    if dry_run:
        log.info(
            "[DRY-RUN] %s — title=%s, pages=%d",
            label,
            title,
            page_count,
        )
        return "ok"

    # Extract metadata from Solr doc
    subtitle = doc.get("subtitle", "")
    if isinstance(subtitle, list):
        subtitle = " ".join(subtitle)

    institution = doc.get("institution_fct", [])
    if isinstance(institution, list):
        institution = institution[0] if institution else ""

    place = doc.get("place_fct", [])
    if isinstance(place, list):
        place = ", ".join(place)

    time_range = doc.get("time_fct", [])
    if isinstance(time_range, list):
        time_range = time_range[0] if time_range else ""

    ref_code = doc.get("label", "")
    if isinstance(ref_code, list):
        ref_code = ref_code[0] if ref_code else ""

    # Build description from available fields
    description_parts = []
    if subtitle:
        description_parts.append(subtitle)
    if institution:
        description_parts.append(f"Archive: {institution}")
    if place:
        description_parts.append(f"Location: {place}")
    description = " | ".join(description_parts)

    metadata = {
        "title": title,
        "description": description,
        "dateRangeText": time_range,
        "referenceCode": ref_code,
        "sourceUrl": f"{DDB_ITEM_BASE}/{item_id}",
        "institution": institution,
        "place": place,
    }
    # Remove empty values from metadata stored in rawSourceMetadata
    metadata_clean = {k: v for k, v in metadata.items() if v}

    # Create record in backend
    try:
        record_id = client.create_record(SOURCE_SYSTEM, item_id, metadata_clean)
    except Exception as e:
        log.error("[FAIL] %s — could not create record: %s", label, e)
        return "failed"

    if page_count == 0:
        log.warning("  No pages available — completing as metadata-only record")
        client.complete_ingest(record_id)
        known_statuses[item_id] = "ocr_pending"
        return "ok"

    # Download and upload each page
    page_images: list[Image.Image] = []
    for seq, page_info in enumerate(pages, start=1):
        page_label = page_info.get("label", f"page_{seq}")
        image_url = page_info.get("image_url", "")

        if not image_url:
            log.warning(
                "  [%d/%d] No image URL for page %s — skipping",
                seq,
                page_count,
                page_label,
            )
            continue

        log.info("  [%d/%d] Downloading %s...", seq, page_count, page_label)
        try:
            image_bytes = session.download_image(image_url)

            # Convert to PIL Image and ensure RGB
            img = Image.open(BytesIO(image_bytes))
            if img.mode != "RGB":
                img = img.convert("RGB")
            page_images.append(img)

            jpeg_bytes = image_to_jpeg_bytes(img)
            client.upload_page(
                record_id,
                seq,
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
                "  [%d/%d] Failed to download/upload page %s: %s",
                seq,
                page_count,
                page_label,
                e,
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
            log.warning("  Failed to build/upload PDF: %s", e)

    # Mark complete
    try:
        client.complete_ingest(record_id)
        known_statuses[item_id] = "ocr_pending"
    except Exception as e:
        log.error("[FAIL] %s — could not complete ingest: %s", label, e)
        return "failed"

    log.info("[DONE] %s — %d/%d pages ingested", label, len(page_images), page_count)
    return "ok"


def run_scrape(
    docs: list[dict],
    session: DDBSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Process a list of Solr result docs. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, doc in enumerate(docs, start=1):
        item_id = doc.get("id", "?")
        title = doc.get("title", item_id)
        if isinstance(title, list):
            title = title[0] if title else item_id
        log.info("=== [%d/%d] %s ===", i, len(docs), title)

        try:
            result = ingest_document(
                client, session, doc, known_statuses, dry_run=dry_run
            )
            if result == "skipped":
                skipped += 1
            elif result == "ok":
                success += 1
            else:
                failed += 1
        except Exception as e:
            log.error("Failed to ingest %s: %s", item_id, e, exc_info=verbose)
            failed += 1

        if client and scraper_id:
            client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME, success)

        time.sleep(get_config().delay)

    return success, failed, skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-ddb",
        description=(
            "Scrape digitised archival documents from the Deutsche Digitale Bibliothek "
            "(Archivportal-D) via the public Solr search API and IIIF images."
        ),
    )
    parser.add_argument(
        "search",
        nargs="?",
        help="Search term for the DDB Solr index (positional)",
    )
    parser.add_argument(
        "--search",
        dest="search_opt",
        metavar="TERM",
        help="Search term (alternative to positional argument)",
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
        help="Search and log documents but do not upload anything",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=500,
        help="Maximum number of documents to ingest (default: 500)",
    )
    parser.add_argument(
        "--rows",
        type=int,
        default=20,
        help="Number of Solr results per page (default: 20)",
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

    # Resolve search term: positional > --search option > env var
    cfg = Config()
    search_term = args.search or args.search_opt or cfg.search_term
    if not search_term:
        parser.error(
            "Provide a search term as a positional argument, via --search, "
            "or via the DDB_SEARCH_TERM environment variable."
        )

    # Apply config overrides
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
            log.warning("Could not fetch existing statuses: %s", e)

    scraper_id = uuid.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    session = DDBSession()
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        log.info("Searching DDB for: '%s'", search_term)

        # Fetch first page to discover total result count
        first_resp = session.search(search_term, start=0, rows=args.rows)
        response_body = first_resp.get("response", {})
        num_found = response_body.get("numFound", 0)
        log.info("DDB reports %d total matching documents", num_found)

        if num_found == 0:
            log.warning("No documents found. Exiting.")
            sys.exit(0)

        # Collect all docs up to --max-items, paginating through Solr results
        all_docs: list[dict] = []
        start = 0

        while len(all_docs) < args.max_items:
            if start == 0:
                docs = response_body.get("docs", [])
            else:
                time.sleep(cfg.delay)
                page_resp = session.search(search_term, start=start, rows=args.rows)
                docs = page_resp.get("response", {}).get("docs", [])

            if not docs:
                break

            all_docs.extend(docs)
            log.info(
                "Fetched page offset=%d: %d docs (total collected: %d)",
                start,
                len(docs),
                len(all_docs),
            )

            start += args.rows
            if start >= num_found:
                break

        # Trim to max_items
        if len(all_docs) > args.max_items:
            all_docs = all_docs[: args.max_items]

        log.info("Processing %d documents", len(all_docs))

        s, f, sk = run_scrape(
            all_docs, session, client, known_statuses, args.dry_run, args.verbose,
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

    sys.exit(0 if total_failed == 0 else 1)


if __name__ == "__main__":
    main()
