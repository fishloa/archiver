"""Bulk enumeration of VadeMeCum search results.

Combines session, search, and record modules to paginate through
all results for a given search term.
"""

import time
import logging

from .config import get_config
from .session import VadeMeCumSession, BASE
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
        max_items: Safety cap on total items.

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

    if len(all_results) >= total or len(all_results) >= max_items:
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

    while len(all_results) < min(total, max_items):
        log.info("Page row %d/%d...", row, total)
        try:
            html = search_mod.goto_page(session, row, sp, fp)

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
