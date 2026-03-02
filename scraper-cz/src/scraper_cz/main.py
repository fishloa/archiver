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
import uuid

from .config import Config, set_config, get_config
from .client import BackendClient, SOURCE_SYSTEM, wait_for_backend
from .session import VadeMeCumSession
from .enumerator import enumerate_all, probe_for_hidden
from .progress import ProgressTracker
from .record import (
    load_record_detail,
    collect_all_scan_uuids,
)
from .zoomify import download_and_stitch, image_to_jpeg_bytes
from .pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Czech National Archives"

# Page-level retry settings (on top of tile-level retries in zoomify.py)
PAGE_MAX_RETRIES = 5
PAGE_RETRY_BACKOFF = [3, 10, 30, 60, 120]


def _download_page_with_retry(
    folder: str,
    uuid: str,
    session: VadeMeCumSession,
    seq: int,
    total: int,
):
    """Download and stitch a page image with robust retries.

    Retries up to PAGE_MAX_RETRIES times with exponential backoff.
    On each retry the VadeMeCum session is reinitialised in case it expired.

    Returns a PIL Image or None on failure.
    """
    for attempt in range(1, PAGE_MAX_RETRIES + 1):
        img = download_and_stitch(folder, uuid, session=session)
        if img is not None:
            return img
        if attempt >= PAGE_MAX_RETRIES:
            break
        wait = PAGE_RETRY_BACKOFF[attempt - 1]
        log.warning(
            "  [%d/%d] Page download attempt %d/%d failed — retrying in %ds",
            seq,
            total,
            attempt,
            PAGE_MAX_RETRIES,
            wait,
        )
        time.sleep(wait)
        # Reinit session in case cookies/session expired
        try:
            session.reinit()
        except Exception:
            pass
    log.error(
        "  [%d/%d] Failed to download page after %d attempts",
        seq,
        total,
        PAGE_MAX_RETRIES,
    )
    return None


# Target NADs for the Czech National Archives.
# Only NADs with digitised content on VadeMeCum are included.
# NADs 1005, 1075, 1420 have no digitised items as of 2026-02.
TARGET_NADS = [
    (1464, "Německé státní ministerstvo pro Čechy a Moravu"),
    (1799, "Státní tajemník u říšského protektora v Čechách a na Moravě"),
]


def ingest_record(
    client: BackendClient,
    session: VadeMeCumSession,
    rec: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest a single record: metadata, pages, PDF, complete.

    Args:
        client: Backend API client.
        session: VadeMeCum session.
        rec: Record dict from enumeration (must have xid, and optionally
             inv, sig, scans, etc.).
        known_statuses: Pre-fetched map of sourceRecordId -> status.
        dry_run: If True, skip actual uploads.

    Returns:
        "ok", "skipped", or "failed".
    """
    cfg = get_config()
    xid = rec["xid"]
    label = f"inv.{rec.get('inv', '?')} sig.{rec.get('sig', '?')}"

    # Check existing status from pre-fetched map
    if not dry_run:
        current_status = known_statuses.get(xid)
        if current_status and current_status not in ("ingesting",):
            log.info("[SKIP] %s — already %s", label, current_status)
            return "skipped"
        if current_status == "ingesting":
            # Previous ingest was incomplete — delete and re-ingest
            status_info = client.get_status(SOURCE_SYSTEM, xid)
            record_id = status_info.get("id")
            if record_id:
                log.info(
                    "[CLEANUP] %s — deleting incomplete record %s", label, record_id
                )
                client.delete_record(record_id)
                known_statuses.pop(xid, None)

    # Load full record detail
    log.info("[START] %s (xid=%s)", label, xid)
    detail = load_record_detail(session, xid)

    if dry_run:
        log.info(
            "[DRY-RUN] %s — %d scans, detail: %s",
            label,
            detail.get("scans", 0),
            detail.get("title", ""),
        )
        return "ok"

    # Create record in backend
    metadata = {k: v for k, v in detail.items() if k != "entity_ref" and v}
    record_id = client.create_record(SOURCE_SYSTEM, xid, metadata)

    # Get scan UUIDs
    entity_ref = detail.get("entity_ref")
    scan_count = detail.get("scans", 0)

    if not entity_ref or scan_count == 0:
        log.warning("[SKIP] %s — no scans available", label)
        client.complete_ingest(record_id)
        known_statuses[xid] = "ocr_pending"
        return "ok"

    # Load Zoomify viewer page to get scan UUIDs
    zoom_html = session.get_zoomify_page(entity_ref, scan_index=0)
    all_uuids = collect_all_scan_uuids(session, entity_ref, scan_count, zoom_html)
    log.info("  Collected %d/%d scan UUIDs", len(all_uuids), scan_count)

    # Download and upload each page
    page_images = []
    for seq, (folder, scan_uuid) in enumerate(all_uuids, start=1):
        log.info(
            "  [%d/%d] Downloading tiles for %s/%s...",
            seq,
            len(all_uuids),
            folder,
            scan_uuid[:8],
        )

        img = _download_page_with_retry(folder, scan_uuid, session, seq, len(all_uuids))
        if img is None:
            continue

        page_images.append(img)

        # Upload page to backend
        jpeg_bytes = image_to_jpeg_bytes(img)
        client.upload_page(
            record_id,
            seq,
            jpeg_bytes,
            metadata={"folder": folder, "uuid": scan_uuid},
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
    known_statuses[xid] = "ocr_pending"
    log.info("[DONE] %s — %d pages ingested", label, len(page_images))
    return "ok"


def run_scrape(
    records: list[dict],
    session: VadeMeCumSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    dry_run: bool,
    verbose: bool,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Process a list of records. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, rec in enumerate(records, start=1):
        label = f"inv.{rec.get('inv', '?')} sig.{rec.get('sig', '?')}"
        log.info(
            "=== [%d/%d] %s (%d scans) ===",
            i,
            len(records),
            label,
            rec.get("scans", 0),
        )

        try:
            result = ingest_record(
                client, session, rec, known_statuses, dry_run=dry_run
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


def run_fond_scrape(
    nad: int,
    session: VadeMeCumSession,
    client: BackendClient | None,
    known_statuses: dict[str, str],
    progress: ProgressTracker,
    dry_run: bool,
    verbose: bool,
    no_probe: bool = False,
    levels: list[str] | None = None,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Scrape an entire fond by NAD number.

    Phase 1: Enumerate ALL records (no digiCheck filter, max_items=0).
    Phase 2: Probe records with 0 scans to find hidden digitised content.
    Phase 3: Ingest all records with scans.

    Returns (success, failed, skipped).
    """
    log.info(
        "\n============================================================"
        "\n  FOND SCRAPE — NAD %d"
        "\n============================================================",
        nad,
    )

    session.reinit()

    # Phase 1: enumerate without digiCheck
    total, records = enumerate_all(
        session,
        "*",
        digi_only=False,
        levels=levels,
        nad=nad,
        max_items=0,
    )
    log.info("NAD %d: %d/%d records enumerated", nad, len(records), total)

    if not records:
        return 0, 0, 0

    with_scans = [r for r in records if r.get("scans", 0) > 0]
    zero_scan = [r for r in records if r.get("scans", 0) == 0]
    log.info(
        "NAD %d: %d with scans, %d with 0 scans in search results",
        nad,
        len(with_scans),
        len(zero_scan),
    )

    # Phase 2: probe for hidden scans
    hidden: list[dict] = []
    if not no_probe and zero_scan:
        already_probed = progress.probed_count(nad)
        log.info(
            "NAD %d: probing %d records for hidden scans (%d already probed)...",
            nad,
            len(zero_scan),
            already_probed,
        )
        hidden = probe_for_hidden(session, zero_scan, progress, nad)
        log.info("NAD %d: found %d hidden records with scans", nad, len(hidden))

    # Phase 3: ingest all records with scans
    all_to_ingest = with_scans + hidden
    log.info("NAD %d: %d records to ingest", nad, len(all_to_ingest))

    if not all_to_ingest:
        return 0, 0, 0

    total_scans = sum(r.get("scans", 0) for r in all_to_ingest)
    new_records = [
        r
        for r in all_to_ingest
        if r["xid"] not in known_statuses or known_statuses.get(r["xid"]) == "ingesting"
    ]
    log.info(
        "NAD %d: %d new/incomplete, %d already done, %d total scans",
        nad,
        len(new_records),
        len(all_to_ingest) - len(new_records),
        total_scans,
    )

    s, f, sk = run_scrape(
        all_to_ingest,
        session,
        client,
        known_statuses,
        dry_run,
        verbose,
        scraper_id=scraper_id,
    )

    # Mark ingested in progress tracker
    for rec in all_to_ingest:
        if known_statuses.get(rec["xid"]) and known_statuses[rec["xid"]] != "ingesting":
            progress.mark_ingested(nad, rec["xid"])
    progress.save()

    return s, f, sk


def run_repair(
    client: BackendClient,
    session: VadeMeCumSession,
    dry_run: bool = False,
    verbose: bool = False,
) -> tuple[int, int, int]:
    """Find records with missing pages and re-download them.

    1. Fetches all records from the backend for this source system.
    2. Compares actual page count vs expected scan count (from rawSourceMetadata).
    3. For each incomplete record, calls the repair endpoint, downloads missing
       pages from VadeMeCum, uploads them, and re-triggers the pipeline.

    Returns (repaired, failed, skipped).
    """
    import json as jsonmod

    cfg = get_config()
    log.info("Fetching all records from backend for repair analysis...")
    all_records = client.get_all_records(SOURCE_SYSTEM)
    log.info("Found %d records in backend", len(all_records))

    # Find incomplete records
    incomplete = []
    for r in all_records:
        raw = r.get("rawSourceMetadata")
        if not raw:
            continue
        try:
            meta = jsonmod.loads(raw) if isinstance(raw, str) else raw
        except Exception:
            continue
        expected = meta.get("scans", 0)
        actual = r.get("pageCount", 0)
        status = r.get("status", "")
        if expected > 0 and actual > 0 and actual < expected and status != "ingesting":
            incomplete.append(
                {
                    "record_id": r["id"],
                    "xid": r.get("sourceRecordId", ""),
                    "ref": r.get("referenceCode", ""),
                    "expected": expected,
                    "actual": actual,
                    "missing": expected - actual,
                }
            )

    incomplete.sort(key=lambda x: x["missing"], reverse=True)
    total_missing = sum(r["missing"] for r in incomplete)
    log.info(
        "Found %d incomplete records with %d total missing pages",
        len(incomplete),
        total_missing,
    )

    if dry_run:
        for r in incomplete[:20]:
            log.info(
                "  [DRY-RUN] id=%s ref=%s expected=%d actual=%d missing=%d",
                r["record_id"],
                r["ref"],
                r["expected"],
                r["actual"],
                r["missing"],
            )
        if len(incomplete) > 20:
            log.info("  ... and %d more", len(incomplete) - 20)
        return 0, 0, len(incomplete)

    repaired, failed, skipped = 0, 0, 0
    for i, rec in enumerate(incomplete, start=1):
        label = f"id={rec['record_id']} ref={rec['ref']}"
        log.info(
            "=== [%d/%d] Repairing %s (missing %d/%d pages) ===",
            i,
            len(incomplete),
            label,
            rec["missing"],
            rec["expected"],
        )

        try:
            # Step 1: Call repair endpoint to reset record and get existing pages
            repair_data = client.repair_record(rec["record_id"])
            existing_seqs = set(repair_data.get("existingPages", []))
            log.info("  Existing pages: %s", sorted(existing_seqs))

            # Step 2: Load record detail from VadeMeCum
            xid = rec["xid"]
            detail = load_record_detail(session, xid)
            entity_ref = detail.get("entity_ref")
            scan_count = detail.get("scans", 0)

            if not entity_ref or scan_count == 0:
                log.warning("  No scans in VadeMeCum for %s — completing as-is", label)
                client.complete_ingest(rec["record_id"])
                skipped += 1
                continue

            # Step 3: Get all scan UUIDs
            zoom_html = session.get_zoomify_page(entity_ref, scan_index=0)
            all_uuids = collect_all_scan_uuids(
                session, entity_ref, scan_count, zoom_html
            )
            log.info("  Collected %d/%d scan UUIDs", len(all_uuids), scan_count)

            # Step 4: Download and upload only missing pages
            uploaded = 0
            for seq, (folder, scan_uuid) in enumerate(all_uuids, start=1):
                if seq in existing_seqs:
                    continue  # Page already exists

                log.info(
                    "  [%d/%d] Downloading missing page %d...",
                    uploaded + 1,
                    rec["missing"],
                    seq,
                )

                img = _download_page_with_retry(
                    folder, scan_uuid, session, seq, rec["expected"]
                )
                if img is None:
                    continue

                jpeg_bytes = image_to_jpeg_bytes(img)
                client.upload_page(
                    rec["record_id"],
                    seq,
                    jpeg_bytes,
                    metadata={"folder": folder, "uuid": scan_uuid},
                )
                uploaded += 1
                time.sleep(cfg.delay * 0.2)

            # Step 5: Re-upload PDF with all pages and complete
            log.info("  Uploaded %d missing pages, completing ingest...", uploaded)
            client.complete_ingest(rec["record_id"])
            repaired += 1
            log.info("[DONE] %s — %d pages added", label, uploaded)

        except Exception as e:
            log.error("Failed to repair %s: %s", label, e, exc_info=verbose)
            failed += 1

        time.sleep(cfg.delay)

    return repaired, failed, skipped


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
        "--all-nads",
        action="store_true",
        help="Scrape all digitised items from target NADs (1005, 1464, 1075, "
        "1420, 1799). No search term needed — enumerates entire fonds.",
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
        help="Filter by a single NAD number",
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
        help="Maximum number of items to enumerate per NAD (default: 5000)",
    )
    parser.add_argument(
        "--fond",
        type=int,
        nargs="+",
        metavar="NAD",
        help="Scrape entire fond(s) by NAD number — enumerates all records, "
        "probes for hidden scans, and ingests everything",
    )
    parser.add_argument(
        "--no-probe",
        action="store_true",
        help="Skip Phase 2 probing for hidden scans (faster, misses hidden docs)",
    )
    parser.add_argument(
        "--progress-file",
        metavar="FILE",
        help="Path to JSON progress file for crash recovery",
    )
    parser.add_argument(
        "--repair",
        action="store_true",
        help="Find records with missing pages and re-download them. "
        "Compares actual vs expected page counts and fills in the gaps.",
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

    if (
        not args.term
        and not args.all
        and not args.all_nads
        and not args.fond
        and not args.repair
    ):
        parser.error(
            "Provide a search term, --all FILE, --all-nads, --fond NAD, or --repair"
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

    session = VadeMeCumSession()
    progress = ProgressTracker(args.progress_file)
    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        if args.repair:
            # Repair mode: find and fix incomplete records
            if client is None:
                wait_for_backend(cfg.require_backend())
                client = BackendClient()
            s, f, sk = run_repair(
                client, session, dry_run=args.dry_run, verbose=args.verbose
            )
            total_success += s
            total_failed += f
            total_skipped += sk

        elif args.fond:
            # Full-fond scrape mode
            for nad_num in args.fond:
                try:
                    s, f, sk = run_fond_scrape(
                        nad_num,
                        session,
                        client,
                        known_statuses,
                        progress,
                        dry_run=args.dry_run,
                        verbose=args.verbose,
                        no_probe=args.no_probe,
                        levels=args.levels,
                        scraper_id=scraper_id,
                    )
                    total_success += s
                    total_failed += f
                    total_skipped += sk
                except Exception as e:
                    log.error(
                        "Error scraping NAD %d: %s", nad_num, e, exc_info=args.verbose
                    )

        elif args.all:
            # Load from JSON file
            log.info("Loading records from %s", args.all)
            with open(args.all) as f:
                records = json.load(f)
            log.info("Loaded %d records", len(records))

            s, f, sk = run_scrape(
                records, session, client, known_statuses, args.dry_run, args.verbose,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

        elif args.all_nads:
            # Iterate over all target NADs, enumerating entire fonds
            search_term = args.term or "*"
            for nad_num, nad_name in TARGET_NADS:
                log.info(
                    "\n============================================================"
                    "\n  NAD %d: %s"
                    "\n============================================================",
                    nad_num,
                    nad_name,
                )

                session.reinit()
                total, records = enumerate_all(
                    session,
                    search_term,
                    digi_only=args.digi_only,
                    levels=args.levels,
                    nad=nad_num,
                    max_items=args.max_items,
                )
                log.info(
                    "NAD %d: %d/%d records enumerated", nad_num, len(records), total
                )

                if not records:
                    continue

                total_scans = sum(r.get("scans", 0) for r in records)
                new_records = [
                    r
                    for r in records
                    if r["xid"] not in known_statuses
                    or known_statuses.get(r["xid"]) == "ingesting"
                ]
                log.info(
                    "NAD %d: %d new/incomplete, %d already done, %d total scans",
                    nad_num,
                    len(new_records),
                    len(records) - len(new_records),
                    total_scans,
                )

                s, f, sk = run_scrape(
                    records, session, client, known_statuses, args.dry_run, args.verbose,
                    scraper_id=scraper_id,
                )
                total_success += s
                total_failed += f
                total_skipped += sk

        else:
            # Single search term, optional single NAD
            log.info("Searching VadeMeCum for: '%s'", args.term)
            total, records = enumerate_all(
                session,
                args.term,
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
                len(records),
                total_scans,
                total_scans * 250 / 1024,
            )

            s, f, sk = run_scrape(
                records, session, client, known_statuses, args.dry_run, args.verbose,
                scraper_id=scraper_id,
            )
            total_success += s
            total_failed += f
            total_skipped += sk

    except KeyboardInterrupt:
        log.warning("Interrupted by user")
    finally:
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
