"""CLI entry point for scraper-arolsen.

Searches the Arolsen Archives (International Tracing Service) via their ASMX
web service API, downloads document page images, builds PDFs, and POSTs
everything to the archiver backend API.

Usage:
    scraper-arolsen --search czernin
    scraper-arolsen --search czernin --max-items 100 --dry-run
    python -m scraper_arolsen.main --search "von trapp"
"""

import argparse
import logging
import sys
import time
from io import BytesIO

from PIL import Image

from worker_common.http import wait_for_backend

from .config import Config, set_config, get_config
from .client import BackendClient, DEFAULT_ARCHIVE_ID, SOURCE_SYSTEM
from .session import ArolsenSession, PAGE_SIZE
from .pdf import build_pdf

log = logging.getLogger(__name__)


def _build_description(meta: dict) -> str:
    """Build a human-readable description from Arolsen metadata fields."""
    parts = []
    if meta.get("creator"):
        parts.append(f"Creator: {meta['creator']}")
    if meta.get("collection"):
        parts.append(f"Collection: {meta['collection']}")
    if meta.get("archive"):
        parts.append(f"Archive: {meta['archive']}")
    if meta.get("scope"):
        parts.append(f"Scope: {meta['scope']}")
    if meta.get("language"):
        parts.append(f"Language: {meta['language']}")
    return " | ".join(parts)


def ingest_document(
    client: BackendClient,
    session: ArolsenSession,
    result: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single Arolsen document: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: Arolsen API session.
        result: Result dict from GetPersonList (must contain ``obj`` field).
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, log what would happen but skip all uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    obj = result.get("obj", "")
    title = result.get("title", obj)

    if not obj:
        log.warning("Result has no 'obj' field, skipping: %r", result)
        return "failed"

    # Check existing status from pre-fetched map
    if not dry_run:
        current_status = known_statuses.get(obj)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", title, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, obj)
            backend_record_id = status_info.get("id")
            if backend_record_id:
                log.info("[CLEANUP] %s — deleting incomplete record %s", title, backend_record_id)
                client.delete_record(backend_record_id)
                known_statuses.pop(obj, None)

    log.info("[START] %s (obj=%s)", title, obj)

    # Fetch detailed metadata
    meta = session.get_metadata(obj)
    if not meta:
        log.warning("No metadata returned for obj=%s, using result fields only", obj)
        meta = {}

    # Merge list-level fields into meta for fallback values
    for key in ("title", "archive", "collection"):
        if key not in meta or not meta[key]:
            meta[key] = result.get(key, "")

    if dry_run:
        files = session.get_files(obj)
        log.info(
            "[DRY-RUN] obj=%s title=%r pages=%d meta_keys=%s",
            obj, meta.get("title", ""), len(files), list(meta.keys()),
        )
        return "ok"

    # Build metadata for backend record creation
    source_url = f"https://collections.arolsen-archives.org/en/search/person/{obj}"
    record_metadata: dict = {
        "archive_id": DEFAULT_ARCHIVE_ID,
        "title": meta.get("title") or title,
        "dateRangeText": meta.get("dates", ""),
        "description": _build_description(meta),
        "referenceCode": meta.get("collection", "") or result.get("archive", ""),
        "sourceUrl": source_url,
    }

    # Remove empty string values (but always keep archive_id)
    record_metadata = {k: v for k, v in record_metadata.items() if v or k == "archive_id"}
    # Attach full raw API response for traceability
    record_metadata["raw_arolsen"] = meta

    # Create record in backend
    backend_record_id = client.create_record(SOURCE_SYSTEM, obj, record_metadata)

    # Fetch file listing (pages)
    files = session.get_files(obj)

    if not files:
        log.info("  No pages found for obj=%s — completing as metadata-only", obj)
        client.complete_ingest(backend_record_id)
        known_statuses[obj] = "ocr_pending"
        return "ok"

    log.info("  Found %d page(s) for obj=%s", len(files), obj)

    page_images: list[Image.Image] = []
    for seq, file_entry in enumerate(files, start=1):
        image_url = file_entry.get("folderImage", "")
        file_id = file_entry.get("file", "")

        if not image_url:
            log.warning("  [%d/%d] No folderImage URL in file entry: %r", seq, len(files), file_entry)
            continue

        log.info("  [%d/%d] Downloading page file=%s ...", seq, len(files), file_id)

        image_bytes = session.download_image(image_url)
        if image_bytes is None:
            log.warning("  [%d/%d] Failed to download image from %s", seq, len(files), image_url)
            continue

        try:
            img = Image.open(BytesIO(image_bytes))
            # Ensure RGB format for JPEG/PDF compatibility
            if img.mode != "RGB":
                img = img.convert("RGB")
            page_images.append(img)

            # Re-encode as JPEG for upload
            jpeg_buf = BytesIO()
            img.save(jpeg_buf, format="JPEG", quality=90)
            jpeg_bytes = jpeg_buf.getvalue()

            client.upload_page(
                backend_record_id,
                seq,
                jpeg_bytes,
                metadata={"pageLabel": file_id} if file_id else None,
            )

            time.sleep(cfg.delay * 0.2)
        except Exception as exc:
            log.warning("  [%d/%d] Failed to process image from %s: %s", seq, len(files), image_url, exc)
            continue

    # Build and upload PDF from collected page images
    if page_images:
        log.info("  Building PDF from %d pages...", len(page_images))
        try:
            pdf_bytes = build_pdf(page_images)
            client.upload_pdf(backend_record_id, pdf_bytes)
            log.info("  PDF uploaded (%d bytes)", len(pdf_bytes))
        except Exception as exc:
            log.warning("  Failed to build/upload PDF: %s", exc)

    # Mark record as fully ingested
    client.complete_ingest(backend_record_id)
    known_statuses[obj] = "ocr_pending"
    log.info("[DONE] obj=%s — %d pages ingested", obj, len(page_images))
    return "ok"


def run_scrape(
    results: list[dict],
    session: ArolsenSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
) -> tuple[int, int, int]:
    """Process a list of search results. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, result in enumerate(results, start=1):
        obj = result.get("obj", "?")
        title = result.get("title", obj)
        log.info("=== [%d/%d] obj=%s %s ===", i, len(results), obj, title)

        try:
            outcome = ingest_document(client, session, result, known_statuses, dry_run=dry_run)
            if outcome == "skipped":
                skipped += 1
            elif outcome == "ok":
                success += 1
            else:
                failed += 1
        except Exception as exc:
            log.error("Failed to ingest obj=%s: %s", obj, exc, exc_info=verbose)
            failed += 1

        time.sleep(get_config().delay)

    return success, failed, skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-arolsen",
        description=(
            "Scrape digitised documents from the Arolsen Archives "
            "(collections.arolsen-archives.org)"
        ),
    )
    parser.add_argument(
        "--search",
        metavar="TERM",
        default="",
        help=(
            "Search term for the Arolsen Archives (default: AROLSEN_SEARCH_TERM env var). "
            "Example: 'czernin', 'rothschild', 'klempner'"
        ),
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
        help="Search and enumerate documents but do not upload anything",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=0,
        help="Maximum number of documents to ingest (default: 0 = unlimited)",
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

    # Apply config overrides from CLI args
    cfg = Config()
    if args.backend_url:
        cfg.backend_url = args.backend_url
    if args.delay is not None:
        cfg.delay = args.delay
    set_config(cfg)
    cfg = get_config()

    # Resolve search term: CLI flag > env var
    search_term = args.search or cfg.search_term
    if not search_term:
        parser.error(
            "Provide a search term via --search or the AROLSEN_SEARCH_TERM environment variable"
        )

    # Pre-fetch all known statuses from backend for resume support
    known_statuses: dict[str, str] = {}
    client: BackendClient | None = None
    if not args.dry_run:
        wait_for_backend(cfg.require_backend())
        client = BackendClient()
        log.info("Fetching existing record statuses from backend...")
        known_statuses = client.get_all_statuses(SOURCE_SYSTEM)
        already_done = sum(1 for s in known_statuses.values() if s != "ingesting")
        incomplete = sum(1 for s in known_statuses.values() if s == "ingesting")
        log.info(
            "Backend has %d records (%d complete, %d incomplete)",
            len(known_statuses), already_done, incomplete,
        )

    session = ArolsenSession()
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        log.info("Searching Arolsen Archives for: '%s'", search_term)

        # Initiate search query (stores query server-side keyed by uniqueId)
        if not session.search(search_term):
            log.error("Search query failed — API may be unavailable")
            sys.exit(1)

        # Get total result count
        total_count = session.get_count()
        log.info("Search reports %d total results", total_count)

        if total_count == 0:
            log.warning("No results found for '%s'", search_term)
            sys.exit(0)

        # Apply max-items cap
        effective_count = total_count
        if args.max_items and args.max_items < total_count:
            effective_count = args.max_items
            log.info("Capping at --max-items %d", effective_count)

        # Paginate through results (API max is PAGE_SIZE per request)
        all_results: list[dict] = []
        start_index = 0

        while start_index < effective_count:
            batch_size = min(PAGE_SIZE, effective_count - start_index)
            log.info(
                "Fetching results %d–%d of %d...",
                start_index + 1, start_index + batch_size, effective_count,
            )
            batch = session.get_person_list(start_index, batch_size)
            if not batch:
                log.warning("Empty batch at start_index=%d, stopping pagination", start_index)
                break
            all_results.extend(batch)
            start_index += len(batch)
            if len(batch) < batch_size:
                # API returned fewer than requested — we've reached the end
                break
            time.sleep(cfg.delay)

        log.info("Total results collected: %d", len(all_results))

        if not all_results:
            log.warning("No results to process.")
            sys.exit(0)

        # Ingest all collected results
        s, f, sk = run_scrape(
            all_results, session, client, known_statuses,
            args.dry_run, args.verbose,
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
        total_success, total_failed, total_skipped,
    )


if __name__ == "__main__":
    main()
