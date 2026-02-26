"""Bulk enumeration of VadeMeCum search results.

Combines session, search, and record modules to paginate through
all results for a given search term.
"""

from __future__ import annotations

import time
import logging

from .config import get_config
from .session import VadeMeCumSession, BASE
from .progress import ProgressTracker
from . import search as search_mod
from . import record as record_mod

log = logging.getLogger(__name__)


def enumerate_all(
    session: VadeMeCumSession,
    term: str,
    digi_only: bool = True,
    levels: list[str] | None = None,
    nad: int | None = None,
    max_items: int = 5000,
) -> tuple[int, list[dict]]:
    """Search and paginate through ALL results.

    Args:
        session: Active VadeMeCum session.
        term: Search term.
        digi_only: Only digitised records.
        levels: Search level codes.
        nad: Optional NAD number filter.
        max_items: Safety cap on total items.  Use 0 for no cap.

    Returns:
        (total_count, list_of_record_dicts)
    """
    cfg = get_config()

    # Reinitialize session for fresh tokens
    session.reinit()

    total, html = search_mod.search(
        session, term, digi_only=digi_only, levels=levels, nad=nad,
    )
    if total == 0:
        return 0, []

    # Parse first page
    results = record_mod.parse_page(html)
    all_results = list(results)
    seen = {r["xid"] for r in results}

    log.info("%d total results, got %d on first page", total, len(results))

    effective_cap = total if max_items == 0 else min(total, max_items)

    if len(all_results) >= effective_cap:
        return total, all_results

    # Get form params for pagination
    sp, fp = search_mod.extract_form_params(html)
    if not sp:
        # Fallback: try the forward link
        fwd = search_mod.extract_forward_link(html)
        if fwd:
            import urllib.parse
            sp_fwd, row = fwd
            url = (
                f"{BASE}/PaginatorResult.action?"
                f"_sourcePage={urllib.parse.quote(sp_fwd, safe='')}&row={row}"
            )
            html = session.get_text(url)
            results = record_mod.parse_page(html)
            for r in results:
                if r["xid"] not in seen:
                    seen.add(r["xid"])
                    all_results.append(r)
        return total, all_results

    # Paginate using form submission (rowTxt jumps to absolute row number)
    page_size = len(results) if results else 12
    row = page_size + 1  # rows are 1-indexed
    retries = 0

    while len(all_results) < effective_cap:
        log.info("Page row %d/%d...", row, total)
        try:
            html = search_mod.goto_page(session, row, sp, fp)

            # Detect session expiry in pagination
            if session.is_expired_response(html):
                log.warning("Session expired during pagination at row %d, reinitialising...", row)
                session.reinit()
                # Re-execute the search from scratch and jump to current row
                _, html = search_mod.search(
                    session, term, digi_only=digi_only, levels=levels, nad=nad,
                )
                new_sp, new_fp = search_mod.extract_form_params(html)
                if new_sp:
                    sp, fp = new_sp, new_fp
                    html = search_mod.goto_page(session, row, sp, fp)
                else:
                    retries += 1
                    if retries > 3:
                        break
                    continue

            # Update form params from new page
            new_sp, new_fp = search_mod.extract_form_params(html)
            if new_sp:
                sp, fp = new_sp, new_fp

            results = record_mod.parse_page(html)
            new_count = 0
            for r in results:
                if r["xid"] not in seen:
                    seen.add(r["xid"])
                    all_results.append(r)
                    new_count += 1

            log.info("  +%d new (total: %d)", new_count, len(all_results))

            if new_count == 0:
                retries += 1
                if retries > 2:
                    log.warning("No new results after %d retries, stopping.", retries)
                    break
            else:
                retries = 0

            row += page_size
            time.sleep(cfg.delay)

        except Exception as e:
            log.warning("Error at row %d: %s", row, e)
            retries += 1
            if retries > 3:
                break
            time.sleep(cfg.delay * 4)

    return total, all_results


def probe_for_hidden(
    session: VadeMeCumSession,
    zero_scan_records: list[dict],
    progress: ProgressTracker,
    nad: int,
) -> list[dict]:
    """Probe records that showed 0 scans in search results.

    Loads the permalink page for each record to check the actual
    scan count.  Records with scans > 0 are "hidden" — they have
    digitised content that doesn't appear in the digiCheck search.

    Args:
        session: Active VadeMeCum session.
        zero_scan_records: Records from enumerate_all() with scans == 0.
        progress: Progress tracker for crash recovery.
        nad: NAD number (for progress tracking).

    Returns:
        List of record dicts that have hidden scans (updated with
        scan count and entity_ref from the permalink page).
    """
    cfg = get_config()
    hidden: list[dict] = []
    total = len(zero_scan_records)

    for i, rec in enumerate(zero_scan_records, start=1):
        xid = rec["xid"]

        if progress.is_probed(nad, xid):
            continue

        log.info("  Probing [%d/%d] xid=%s...", i, total, xid[:12])

        try:
            html = session.get_permalink_page_safe(xid)
            scans = record_mod.extract_scan_count(html)
            entity_ref = record_mod.extract_entity_ref(html)

            progress.mark_probed(nad, xid)

            if scans > 0:
                log.info("  HIDDEN: xid=%s has %d scans!", xid[:12], scans)
                rec = dict(rec, scans=scans, entity_ref=entity_ref)
                hidden.append(rec)
                progress.mark_hidden(nad, xid)

            time.sleep(cfg.delay)

        except Exception as e:
            log.warning("  Error probing xid=%s: %s", xid[:12], e)
            time.sleep(cfg.delay * 2)

    progress.save()
    log.info(
        "Probing complete: %d/%d records had hidden scans",
        len(hidden), total,
    )
    return hidden
