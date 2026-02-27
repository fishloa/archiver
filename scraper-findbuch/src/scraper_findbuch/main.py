"""CLI entry point for scraper-findbuch.

Searches findbuch.at (Austrian Victims/Property Database), paginates through results,
fetches record details, and ingests metadata to the backend API.
"""

import argparse
import logging
import sys
import time
import urllib.parse

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM
from worker_common.http import wait_for_backend
from .session import FindbuchSession
from .parser import parse_search_results, get_total_pages, get_result_count, parse_detail_page

log = logging.getLogger(__name__)


def ingest_record(
    client: BackendClient,
    session: FindbuchSession,
    result: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single record: fetch detail, parse metadata, create record.

    Args:
        client: Backend API client.
        session: Findbuch session.
        result: Result dict from search (must have detail_url).
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    detail_url = result.get("detail_url", "")
    title = result.get("title", "?")

    # Extract source record ID from detail URL
    # Format: findbuch.at/.../<id> or similar
    # Use URL as source ID if no better option
    source_record_id = detail_url.split("/")[-1] or detail_url

    # Check existing status from pre-fetched map
    if not dry_run:
        current_status = known_statuses.get(source_record_id)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", title, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, source_record_id)
            record_id = status_info.get("id")
            if record_id:
                log.info("[CLEANUP] %s — deleting incomplete record %s", title, record_id)
                client.delete_record(record_id)
                known_statuses.pop(source_record_id, None)

    # Load record detail page
    log.info("[START] %s (%s)", title, source_record_id)
    detail_html = session.get_detail(detail_url)

    if dry_run:
        log.info("[DRY-RUN] %s", title)
        return "ok"

    # Parse detail page
    detail_data = parse_detail_page(detail_html, detail_url)

    # Build metadata for backend
    metadata = {
        "archive_id": 3,  # findbuch.at
        "title": (
            (detail_data.get("surname", "") + ", " + detail_data.get("forename", "")).strip().rstrip(",")
            or title
        ),
        "referenceCode": detail_data.get("file_number", ""),
        "dateRangeText": detail_data.get("dateOfBirth", ""),
        "description": _build_description(detail_data),
        "sourceUrl": detail_url,
    }

    # Add optional fields
    if detail_data.get("profession"):
        metadata["profession"] = detail_data["profession"]
    if detail_data.get("address"):
        metadata["address"] = detail_data["address"]
    if detail_data.get("nationality"):
        metadata["nationality"] = detail_data["nationality"]
    if detail_data.get("religion"):
        metadata["religion"] = detail_data["religion"]
    if detail_data.get("race"):
        metadata["race"] = detail_data["race"]

    # Store raw detail data as metadata
    metadata["rawDetail"] = detail_data

    # Create record in backend
    record_id = client.create_record(SOURCE_SYSTEM, source_record_id, metadata)

    # Mark complete (no images/PDFs for findbuch.at — metadata only)
    client.complete_ingest(record_id)
    known_statuses[source_record_id] = "ocr_pending"
    log.info("[DONE] %s — metadata ingested", title)
    return "ok"


def _build_description(detail_data: dict) -> str:
    """Build a description from detail data fields."""
    parts = []
    if detail_data.get("profession"):
        parts.append(f"Profession: {detail_data['profession']}")
    if detail_data.get("address"):
        parts.append(f"Address: {detail_data['address']}")
    if detail_data.get("nationality"):
        parts.append(f"Nationality: {detail_data['nationality']}")
    if detail_data.get("religion"):
        parts.append(f"Religion: {detail_data['religion']}")
    if detail_data.get("race"):
        parts.append(f"Race: {detail_data['race']}")
    if detail_data.get("spouse_info"):
        parts.append(f"Spouse: {detail_data['spouse_info']}")
    if detail_data.get("assets"):
        parts.append(f"Assets: {detail_data['assets']}")
    if detail_data.get("archive_info"):
        parts.append(f"Archive: {detail_data['archive_info']}")
    return " | ".join(parts)


def run_scrape(
    results: list[dict],
    session: FindbuchSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
) -> tuple[int, int, int]:
    """Process a list of search results. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, result in enumerate(results, start=1):
        title = result.get("title", "?")
        log.info(
            "=== [%d/%d] %s ===",
            i, len(results), title,
        )

        try:
            result_status = ingest_record(client, session, result, known_statuses, dry_run=dry_run)
            if result_status == "skipped":
                skipped += 1
            elif result_status == "ok":
                success += 1
            else:
                failed += 1
        except Exception as e:
            log.error("Failed to ingest %s: %s", title, e, exc_info=verbose)
            failed += 1

        time.sleep(get_config().delay)

    return success, failed, skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-findbuch",
        description="Scrape records from findbuch.at Austrian Victims/Property Database",
    )
    parser.add_argument(
        "term",
        nargs="?",
        help="Search term for findbuch.at search",
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
        help="Search and log records but do not upload anything",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=1000,
        help="Maximum number of items to ingest (default: 1000)",
    )
    parser.add_argument(
        "--max-pages",
        type=int,
        default=None,
        help="Maximum number of pages to fetch (default: all)",
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

    if not args.term:
        parser.error("Provide a search term")

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
            len(known_statuses), already_done, incomplete,
        )

    session = FindbuchSession()
    try:
        session.login()
    except RuntimeError as e:
        log.error("Login failed: %s", e)
        log.error("Set FINDBUCH_USERNAME and FINDBUCH_PASSWORD env vars. "
                  "Register at https://www.findbuch.at/registration-for-individuals")
        sys.exit(1)
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        # URL-encode search term for safe URL construction
        encoded_term = urllib.parse.quote(args.term)

        log.info("Searching findbuch.at for: '%s'", args.term)

        # Fetch first page to get total count
        time.sleep(get_config().delay)
        first_page_html = session.search(encoded_term, page=1)
        result_count = get_result_count(first_page_html)
        if result_count:
            log.info("Search reports %d total results", result_count)
        total_pages = get_total_pages(first_page_html)

        if args.max_pages:
            total_pages = min(total_pages, args.max_pages)

        log.info("Found %d page(s) of results", total_pages)

        all_results = []

        for page_num in range(1, total_pages + 1):
            log.info("Fetching page %d/%d...", page_num, total_pages)

            if page_num == 1:
                page_html = first_page_html
            else:
                time.sleep(get_config().delay)
                page_html = session.search(encoded_term, page=page_num)

            results = parse_search_results(page_html)
            all_results.extend(results)

            log.info("Page %d: found %d results (total: %d)", page_num, len(results), len(all_results))

            if len(all_results) >= args.max_items:
                all_results = all_results[:args.max_items]
                break

        log.info("Total results enumerated: %d", len(all_results))

        if not all_results:
            log.warning("No results found.")
            sys.exit(0)

        # Process results
        s, f, sk = run_scrape(
            all_results, session, client, known_statuses,
            args.dry_run, args.verbose
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
