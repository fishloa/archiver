"""CLI entry point for scraper-ebadatelna.

Pulls inventory units (archiválie) from ebadatelna.cz — the reading room of the
Czech Archive of Security Forces — downloads their scans, builds a PDF and
POSTs the lot to the backend.

Three ways in:

* ``ingest <node-id>…`` — named units, which is what targeted research wants.
* ``search <query>`` — OCR fulltext, ranked by hit count. ``--list`` to look
  before ingesting; the matcher is loose, so looking first is usually right.
* ``browse <collection-id>`` — walk a fond and take every digitised unit. Whole
  fonds run to hundreds of thousands of scans, so pair it with ``--max-items``.

Scans and OCR search both need a logged-in **and identity-verified** account.
An unverified one searches happily and returns zero rows for everything, so the
scraper refuses to treat a nil result as an answer.
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
from .session import (
    EBadatelnaSession,
    IMAGE_TYPE_MULTIMEDIA,
    IMAGE_TYPE_STANDARD,
    normalise_signature_id,
)
from worker_common.pdf import build_pdf

log = logging.getLogger(__name__)

SCRAPER_NAME = "Czech Archive of Security Forces"
ARCHIVE_ID = 2


def image_to_jpeg_bytes(img: Image.Image, quality: int = 95) -> bytes:
    """Convert a PIL Image to JPEG bytes."""
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def build_metadata(session: EBadatelnaSession, node_id: str, item: dict) -> dict:
    """Assemble record metadata for one unit.

    The search grid and the tree both truncate the unit's ``Name``; the full
    archival description comes from ``GetNodeInfo`` and is worth the extra call
    — it is where the substance of a file is stated.
    """
    node_id = normalise_signature_id(node_id)
    info = session.node_info(node_id)
    path = session.fond_path(node_id)

    description = info["description"]
    if path:
        description = (
            f"{description}\n\nArchivní kontext: {path}" if description else path
        )
    if item.get("ExportNoteMessage"):
        note = item["ExportNoteMessage"]
        description = f"{description}\n\n{note}" if description else note

    title = (item.get("Name") or info["description"].split("\n")[0] or node_id).strip()
    return {
        "title": title[:500],
        "description": description,
        "referenceCode": item.get("Signature") or info["signature"],
        "dateRangeText": item.get("Dating") or "",
        "sourceUrl": session.record_url(node_id),
        "archive_id": ARCHIVE_ID,
        "item": item,
        "fondPath": path,
    }


def ingest_record(
    client: BackendClient,
    session: EBadatelnaSession,
    item: dict,
    known_statuses: dict[str, str],
    dry_run: bool = False,
) -> str:
    """Ingest one unit: metadata, pages, PDF, complete.

    ``item`` may come from either source. Tree rows carry ``Leaf``/``HasFiles``/
    ``FileCount``; OCR search rows carry none of those, so the real scan count
    is read from ``GetSignatureImages`` rather than trusted from the row.

    Returns "ok", "skipped" or "failed".
    """
    cfg = get_config()
    node_id = normalise_signature_id(item["Id"])
    title = item.get("Name", "")
    label = f"{node_id} - {title[:60]}"

    if not dry_run:
        current_status = known_statuses.get(node_id)
        if current_status and current_status != "ingesting":
            log.info("[SKIP] %s — already %s", label, current_status)
            return "skipped"
        if current_status == "ingesting":
            # A previous run died part-way; the record holds an unknown subset
            # of its pages, so start it again rather than appending to it.
            status_info = client.get_status(SOURCE_SYSTEM, node_id)
            record_id = status_info.get("id")
            if record_id:
                log.info(
                    "[CLEANUP] %s — deleting incomplete record %s", label, record_id
                )
                client.delete_record(record_id)
                known_statuses.pop(node_id, None)

    # A container node has no scans of its own.
    if item.get("Leaf") == 0:
        log.info("[SKIP] %s — a container, not an inventory unit", label)
        return "skipped"

    if not session.verified:
        log.error(
            "[SKIP] %s — scans need an identity-verified account; "
            "check EBADATELNA_EMAIL/PASSWORD and the account's verification",
            label,
        )
        return "failed"

    types = session.image_types(node_id)
    standard = [t for t in types if t.get("Value") == IMAGE_TYPE_STANDARD]
    if not types:
        log.info("[SKIP] %s — nothing digitised", label)
        return "skipped"
    if not standard:
        kinds = ", ".join(f"{t.get('Name')} ({t.get('Value')})" for t in types)
        if all(t.get("Value") == IMAGE_TYPE_MULTIMEDIA for t in types):
            log.info("[SKIP] %s — audio/video only, no page scans: %s", label, kinds)
        else:
            log.info("[SKIP] %s — no standard scan set: %s", label, kinds)
        return "skipped"

    expected = standard[0].get("Count", 0)
    log.info("[START] %s (%d scans)", label, expected)

    if dry_run:
        log.info("[DRY-RUN] %s — would ingest %d scans", label, expected)
        return "ok"

    metadata = build_metadata(session, node_id, item)
    meta, tokens = session.all_page_tokens(node_id)
    if not tokens:
        log.warning("[SKIP] %s — %d scans announced, none returned", label, expected)
        return "failed"
    if len(tokens) != meta.get("TotalImages", len(tokens)):
        log.warning(
            "  %s: got %d tokens for %d scans", label, len(tokens), meta["TotalImages"]
        )

    record_id = client.create_record(SOURCE_SYSTEM, node_id, metadata)

    page_images = []
    failures = 0
    for seq, token in enumerate(tokens, start=1):
        try:
            img_bytes = session.get_image(token)
            if not img_bytes or len(img_bytes) < 2000:
                log.warning("  [%d/%d] empty response", seq, len(tokens))
                failures += 1
                continue

            img = Image.open(io.BytesIO(img_bytes))
            if img.mode != "RGB":
                img = img.convert("RGB")
            page_images.append(img)

            client.upload_page(record_id, seq, image_to_jpeg_bytes(img))
            if seq % 25 == 0 or seq == len(tokens):
                log.info("  %d/%d scans", seq, len(tokens))
            time.sleep(cfg.delay * 0.2)
        except Exception as e:
            log.warning("  [%d/%d] failed: %s", seq, len(tokens), e)
            failures += 1

    if not page_images:
        log.error("[FAIL] %s — no scans downloaded", label)
        return "failed"

    try:
        client.upload_pdf(record_id, build_pdf(page_images))
    except Exception as e:
        log.error("  PDF build/upload failed: %s", e)

    client.complete_ingest(record_id)
    known_statuses[node_id] = "ocr_pending"
    log.info(
        "[DONE] %s — %d/%d scans%s",
        label,
        len(page_images),
        len(tokens),
        f", {failures} failed" if failures else "",
    )
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

    if max_items:
        items = items[:max_items]

    for i, item in enumerate(items, start=1):
        node_id = item.get("Id", "?")
        log.info(
            "=== [%d/%d] %s %s ===",
            i,
            len(items),
            item.get("Signature") or node_id,
            (item.get("Name") or "")[:70],
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
            log.error("Failed to ingest %s: %s", node_id, e, exc_info=verbose)
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
    """Walk a collection depth-first and return its digitised leaf units.

    ``Item_Read`` returns every child of a node in one response, so this is a
    plain recursion with no paging.
    """
    log.info("Browsing collection %s…", collection_id)
    all_docs: list[dict] = []
    visited: set[str] = set()

    def traverse(parent_id: str | None, depth: int) -> None:
        if max_items and len(all_docs) >= max_items:
            return
        if parent_id in visited:
            return
        if parent_id:
            visited.add(parent_id)

        try:
            items, _ = session.item_read(parent_id=parent_id)
        except Exception as e:
            log.warning("Failed to read children of %s: %s", parent_id, e)
            return

        for item in items:
            if max_items and len(all_docs) >= max_items:
                return
            log.debug(
                "  %s%s (Leaf=%s, HasFiles=%s, FileCount=%s)",
                "  " * depth,
                (item.get("Name") or "")[:60],
                item.get("Leaf"),
                item.get("HasFiles"),
                item.get("FileCount"),
            )
            if item.get("Leaf") == 1:
                if item.get("HasFiles") == 1 or item.get("FileCount"):
                    all_docs.append(item)
            else:
                traverse(item["Id"], depth + 1)

    traverse(collection_id, 0)
    log.info("Collection %s: %d digitised units", collection_id, len(all_docs))
    return all_docs


def search_ocr(
    session: EBadatelnaSession,
    search_text: str,
    max_items: int | None = None,
    date_from: int = 1885,
    date_to: int = 1993,
) -> list[dict]:
    """OCR fulltext search, most hits first.

    The matcher is stemmed and loose and searches catalogue metadata alongside
    the scans, so results want reading before they are ingested — hence
    ``--list``.
    """
    log.info("OCR search: %s", search_text)
    rows, total = session.ocr_search(
        search_text, page=1, page_size=200, date_from=date_from, date_to=date_to
    )
    page = 1
    while len(rows) < total and (not max_items or len(rows) < max_items):
        page += 1
        more, total = session.ocr_search(
            search_text, page=page, page_size=200, date_from=date_from, date_to=date_to
        )
        if not more:
            break
        rows.extend(more)

    rows.sort(key=lambda r: -(r.get("TotalNum") or 0))
    log.info("OCR search %r: %d units", search_text, total)
    return rows


def print_search_results(
    session: EBadatelnaSession, rows: list[dict], query: str
) -> None:
    """Print hits as ``count  signature  id  name``, with the matching scans."""
    for row in rows:
        name = " ".join((row.get("Name") or "").split())
        print(
            f"{row.get('TotalNum', 0):>5}  {row.get('Signature') or '':<18} "
            f"{row.get('Id'):<9} {name[:110]}"
        )
        try:
            hits = session.folder_hits(row["Id"], query)
        except Exception as e:
            log.debug("folder_hits failed for %s: %s", row.get("Id"), e)
            continue
        if hits:
            scans = ", ".join(str(h["scan_number"]) for h in hits if h["scan_number"])
            print(f"         scans: {scans}")


def main():
    parser = argparse.ArgumentParser(
        prog="scraper-ebadatelna",
        description="Scrape digitised records from the Czech Archive of Security Forces",
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    search_parser = subparsers.add_parser("search", help="OCR full-text search")
    search_parser.add_argument("query", help="Search one surname or term at a time")
    search_parser.add_argument(
        "--list",
        action="store_true",
        help="Print the hits and the matching scan numbers; ingest nothing",
    )
    search_parser.add_argument("--date-from", type=int, default=1885)
    search_parser.add_argument("--date-to", type=int, default=1993)

    browse_parser = subparsers.add_parser("browse", help="Browse a collection by ID")
    browse_parser.add_argument("collection_id", help="Collection node id, e.g. s1472")

    ingest_parser = subparsers.add_parser(
        "ingest", help="Ingest named inventory units by node id"
    )
    ingest_parser.add_argument(
        "node_ids",
        nargs="+",
        help="Node ids, with or without the f prefix (626290 or f626290)",
    )

    for sp in [search_parser, browse_parser, ingest_parser]:
        sp.add_argument(
            "--backend-url", help="Backend API base URL (overrides BACKEND_URL)"
        )
        sp.add_argument(
            "--delay",
            type=float,
            help="Seconds between requests (overrides SCRAPER_DELAY)",
        )
        sp.add_argument(
            "--dry-run",
            action="store_true",
            help="Enumerate and log records but upload nothing",
        )
        sp.add_argument(
            "--max-items", type=int, help="Maximum number of units to ingest"
        )
        sp.add_argument("-v", "--verbose", action="store_true", help="Debug logging")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    cfg = Config()
    if args.backend_url:
        cfg.backend_url = args.backend_url
    if args.delay is not None:
        cfg.delay = args.delay
    set_config(cfg)

    listing_only = args.command == "search" and args.list
    if listing_only:
        args.dry_run = True

    known_statuses: dict[str, str] = {}
    client = None
    if not args.dry_run:
        try:
            wait_for_backend(cfg.require_backend())
            client = BackendClient()
            log.info("Fetching existing record statuses…")
            known_statuses = client.get_all_statuses(SOURCE_SYSTEM)
            incomplete = sum(1 for s in known_statuses.values() if s == "ingesting")
            log.info(
                "Backend holds %d records (%d complete, %d incomplete)",
                len(known_statuses),
                len(known_statuses) - incomplete,
                incomplete,
            )
        except Exception as e:
            log.warning("Could not fetch existing statuses: %s", e)
            if not cfg.backend_url:
                log.warning("BACKEND_URL not set; continuing as a dry run")
                args.dry_run = True

    scraper_id = uuid.uuid4().hex[:12]
    if client:
        client.heartbeat(scraper_id, SOURCE_SYSTEM, SCRAPER_NAME)

    session = EBadatelnaSession()
    session.login()
    if not session.verified:
        log.warning(
            "Not verified: the fond tree and descriptions still work, "
            "but OCR search returns zero rows for every query and scans are refused"
        )

    total_success, total_failed, total_skipped = 0, 0, 0

    try:
        if args.command == "search":
            items = search_ocr(
                session,
                args.query,
                max_items=args.max_items,
                date_from=args.date_from,
                date_to=args.date_to,
            )
            if not items:
                log.warning("No hits.")
                sys.exit(0)
            if listing_only:
                print_search_results(
                    session, items[: args.max_items or len(items)], args.query
                )
                sys.exit(0)

        elif args.command == "browse":
            items = browse_collection(
                session, args.collection_id, max_items=args.max_items
            )
            if not items:
                log.warning("No digitised units found.")
                sys.exit(0)

        else:  # ingest
            items = [{"Id": normalise_signature_id(n)} for n in args.node_ids]

        log.info("Processing %d units", len(items))
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
        "Finished: %d ingested, %d failed, %d skipped",
        total_success,
        total_failed,
        total_skipped,
    )


if __name__ == "__main__":
    main()
