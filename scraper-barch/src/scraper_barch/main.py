"""CLI entry point for scraper-barch.

Ingests digitised files from the German Bundesarchiv's Invenio system
(invenio.bundesarchiv.de) into the archiver backend.

This scraper is deliberately UUID-driven, not a crawler: the catalogue
search side of Invenio is a JSF/PrimeFaces app that needs session +
ViewState POSTs, and there is no REST API and no OAI-PMH (both probed,
404). The operator supplies UUIDs obtained manually from the Invenio UI
(positional arguments and/or --uuid-file).

TODO: signature -> UUID search would need a headless browser driving the
"Suche ohne Anmeldung" ("Search without login") button on the Invenio
login page. Not implemented here — do not add catalogue crawling code.
"""

import argparse
import logging
import time
import uuid as uuidmod

from worker_common.http import wait_for_backend

from .client import BackendClient, SOURCE_SYSTEM
from .config import Config, get_config, set_config
from .invenio import (
    build_image_url,
    build_title,
    first_item_page_count,
    get_pages,
    parse_viewer_page,
    viewer_url,
)
from .session import InvenioSession
from .zipsource import list_zip_pages, read_zip_page

log = logging.getLogger(__name__)

SCRAPER_NAME = "German Federal Archives (Bundesarchiv)"


def load_uuid_file(path: str) -> list[str]:
    """Read one UUID per line from *path*. Blank lines and '#' comments are skipped."""
    uuids = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                uuids.append(line)
    return uuids


def ingest_record(
    client: BackendClient | None,
    session: InvenioSession,
    record_uuid: str,
    dry_run: bool = False,
    force: bool = False,
    max_pages: int | None = None,
) -> str:
    """Ingest a single record by Invenio UUID.

    Returns "ok", "skipped", or raises on failure.
    """
    cfg = get_config()
    label = f"uuid={record_uuid}"

    log.info("[START] %s", label)
    html = session.get_viewer_page(record_uuid)
    data = parse_viewer_page(html)

    signature = data.get("title", "")
    title = build_title(data)
    pages = get_pages(data)
    if max_pages is not None:
        pages = pages[:max_pages]

    if dry_run:
        log.info(
            "[DRY-RUN] %s — signature=%s, title=%s, %d pages",
            label,
            signature,
            title,
            len(pages),
        )
        return "ok"

    assert client is not None  # only None in dry-run mode

    if not force:
        current_status = client.get_status(SOURCE_SYSTEM, signature)
        if current_status:
            log.info(
                "[SKIP] %s — already exists as %s (status=%s)",
                label,
                signature,
                current_status.get("status"),
            )
            return "skipped"

    metadata = {
        "title": title,
        "referenceCode": signature,
        "sourceUrl": viewer_url(record_uuid),
        "raw": data,
    }
    record_id = client.create_record(SOURCE_SYSTEM, signature, metadata)

    for seq, file_entry in enumerate(pages, start=1):
        filename = file_entry.get("filename")
        image_url = build_image_url(data, filename)
        log.info("  [%d/%d] Downloading %s...", seq, len(pages), filename)
        image_bytes = session.download_image(image_url)

        page_metadata = {}
        if file_entry.get("width"):
            page_metadata["width"] = file_entry["width"]
        if file_entry.get("height"):
            page_metadata["height"] = file_entry["height"]

        client.upload_page(record_id, seq, image_bytes, metadata=page_metadata)
        time.sleep(cfg.throttle)

    client.complete_ingest(record_id)
    log.info("[DONE] %s — %d pages ingested", label, len(pages))
    return "ok"


def ingest_from_zip(
    client: BackendClient | None,
    session: InvenioSession | None,
    zip_path: str,
    record_uuid: str | None = None,
    signature: str | None = None,
    dry_run: bool = False,
    force: bool = False,
    max_pages: int | None = None,
) -> str:
    """Ingest a record from a local zip of page JPEGs (Invenio's whole-file download).

    Metadata comes from the authoritative viewer page when *record_uuid* is
    given (and the zip's page count is cross-checked against
    items[0].files); otherwise *signature* is used verbatim for the offline
    case, which skips the cross-check.

    No throttle is applied between page uploads here — there is no remote
    fetch to be polite about, since pages come from the local zip. The
    ResilientClient backend client still retries transient upload failures.
    """
    label = f"zip={zip_path}"
    all_pages = list_zip_pages(zip_path)  # full, uncapped — used for the cross-check

    data: dict | None = None
    title: str
    sig: str
    source_url = ""

    if record_uuid:
        assert session is not None
        html = session.get_viewer_page(record_uuid)
        data = parse_viewer_page(html)
        sig = data.get("title", "")
        title = build_title(data)
        source_url = viewer_url(record_uuid)

        expected_count = first_item_page_count(data)
        if expected_count != len(all_pages):
            raise ValueError(
                f"Zip page count ({len(all_pages)}) does not match viewer metadata "
                f"items[0].files count ({expected_count}) for uuid={record_uuid} — "
                "the download may be truncated or partial"
            )
    else:
        sig = signature or ""
        title = signature or ""

    label = f"{label} signature={sig}"

    pages = all_pages if max_pages is None else all_pages[:max_pages]

    if dry_run:
        log.info(
            "[DRY-RUN] %s — signature=%s, title=%s, %d pages (from zip)",
            label,
            sig,
            title,
            len(pages),
        )
        return "ok"

    assert client is not None  # only None in dry-run mode

    if not force:
        current_status = client.get_status(SOURCE_SYSTEM, sig)
        if current_status:
            log.info(
                "[SKIP] %s — already exists (status=%s)",
                label,
                current_status.get("status"),
            )
            return "skipped"

    metadata = {
        "title": title,
        "referenceCode": sig,
        "sourceUrl": source_url,
        "raw": data or {},
    }
    record_id = client.create_record(SOURCE_SYSTEM, sig, metadata)

    for seq, entry_name in pages:
        log.info("  [%d/%d] Reading %s from zip...", seq, len(pages), entry_name)
        image_bytes = read_zip_page(zip_path, entry_name)
        client.upload_page(record_id, seq, image_bytes)

    client.complete_ingest(record_id)
    log.info("[DONE] %s — %d pages ingested from zip", label, len(pages))
    return "ok"


def run_scrape(
    uuids: list[str],
    session: InvenioSession,
    client: BackendClient | None,
    dry_run: bool,
    force: bool,
    max_pages: int | None,
    verbose: bool,
    scraper_id: str = "",
) -> tuple[int, int, int]:
    """Process a list of UUIDs. Returns (success, failed, skipped)."""
    success, failed, skipped = 0, 0, 0

    for i, record_uuid in enumerate(uuids, start=1):
        log.info("=== [%d/%d] uuid=%s ===", i, len(uuids), record_uuid)
        try:
            result = ingest_record(
                client,
                session,
                record_uuid,
                dry_run=dry_run,
                force=force,
                max_pages=max_pages,
            )
            if result == "skipped":
                skipped += 1
            else:
                success += 1
        except Exception as e:
            log.error("Failed to ingest uuid=%s: %s", record_uuid, e, exc_info=verbose)
            failed += 1

        if client and scraper_id:
            client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME, success)

    return success, failed, skipped


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-barch",
        description="Ingest digitised files from the German Bundesarchiv Invenio system",
    )
    parser.add_argument(
        "uuids",
        nargs="*",
        help="Invenio record UUID(s) to ingest",
    )
    parser.add_argument(
        "--uuid-file",
        metavar="FILE",
        help="Path to a file with one UUID per line ('#' comments allowed)",
    )
    parser.add_argument(
        "--from-zip",
        metavar="PATH",
        help="Ingest page images from a local zip of Invenio's whole-file "
        "download (avoids re-downloading every page). Requires --uuid or "
        "--signature.",
    )
    parser.add_argument(
        "--uuid",
        dest="single_uuid",
        metavar="UUID",
        help="Single record UUID to fetch metadata from, for use with --from-zip",
    )
    parser.add_argument(
        "--signature",
        metavar="SIG",
        help="Record signature to use with --from-zip when no --uuid is given "
        "(offline case — skips the zip page-count cross-check)",
    )
    parser.add_argument(
        "--backend-url",
        help="Backend API base URL (overrides BACKEND_URL env var)",
    )
    parser.add_argument(
        "--throttle",
        type=float,
        help="Seconds to wait between page image downloads "
        "(overrides SCRAPER_THROTTLE env var, default 1.0)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Fetch and print metadata and page count; do not download images "
        "or write anything to the backend",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-ingest even if a record already exists for this signature",
    )
    parser.add_argument(
        "--max-pages",
        type=int,
        help="Cap the number of pages ingested per record (for testing)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable debug logging",
    )

    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    if args.from_zip and not args.single_uuid and not args.signature:
        parser.error("--from-zip requires --uuid or --signature")

    uuids: list[str] = []
    if not args.from_zip:
        uuids = list(args.uuids)
        if args.uuid_file:
            uuids.extend(load_uuid_file(args.uuid_file))
        # Dedupe, preserving order
        uuids = list(dict.fromkeys(uuids))
        if not uuids:
            parser.error("Provide one or more UUIDs and/or --uuid-file (or --from-zip)")

    cfg = Config()
    if args.backend_url:
        cfg.backend_url = args.backend_url
    if args.throttle is not None:
        cfg.throttle = args.throttle
    set_config(cfg)

    client = None
    if not args.dry_run:
        wait_for_backend(cfg.require_backend())
        client = BackendClient()

    scraper_id = uuidmod.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    if args.from_zip:
        # Only need an Invenio session if we're fetching authoritative
        # metadata from the viewer page (--uuid). The offline --signature
        # path never talks to invenio.bundesarchiv.de.
        session = InvenioSession() if args.single_uuid else None
        try:
            result = ingest_from_zip(
                client,
                session,
                args.from_zip,
                record_uuid=args.single_uuid,
                signature=args.signature,
                dry_run=args.dry_run,
                force=args.force,
                max_pages=args.max_pages,
            )
            log.info("Finished (zip): %s", result)
        finally:
            if session:
                session.close()
            if client:
                client.close()
        return

    session = InvenioSession()

    try:
        success, failed, skipped = run_scrape(
            uuids,
            session,
            client,
            args.dry_run,
            args.force,
            args.max_pages,
            args.verbose,
            scraper_id=scraper_id,
        )
    except KeyboardInterrupt:
        log.warning("Interrupted by user")
        success, failed, skipped = 0, 0, 0
    finally:
        session.close()
        if client:
            client.close()

    log.info("Finished: %d success, %d failed, %d skipped", success, failed, skipped)


if __name__ == "__main__":
    main()
