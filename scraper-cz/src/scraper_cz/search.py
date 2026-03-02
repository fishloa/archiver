"""VadeMeCum search and pagination.

Ported from enumerate_digi.py -- uses the same regex patterns and
POST-based pagination that work with the VadeMeCum portal.
"""

import re
import logging
import html as htmlmod
import urllib.parse

from .session import VadeMeCumSession, BASE

log = logging.getLogger(__name__)


def search(
    session: VadeMeCumSession,
    term: str,
    digi_only: bool = True,
    levels: list[str] | None = None,
    nad: int | None = None,
) -> tuple[int, str]:
    """Execute a search on VadeMeCum.

    Uses the extended search form (searchType=basic) which supports
    NAD, digi-only, and level filters. The simple form only does fulltext.

    Args:
        session: Active VadeMeCum session.
        term: Fulltext search term (empty string for browse-all).
        digi_only: Only return digitised records.
        levels: List of level codes to search.
            9019 = item, 10026 = inventory, 10060 = fond.
            Default: ["9019"] (item level only).
        nad: Optional NAD number filter.

    Returns:
        (total_count, first_page_html)
    """
    if levels is None:
        levels = ["9019"]

    params = {
        "patternFulltext": term,
        "searchFulltext": "",
        "wordPosition": "1",
        "searchByEntityFields": "true",
        "_sourcePage": session.extended_source_page,
        "__fp": session.extended_fp,
    }
    for lvl in levels:
        params[lvl] = "true"
    if digi_only:
        params["digiCheck"] = "true"
    if nad is not None:
        params["pevaCNAD"] = str(nad)

    html = session.post(f"{BASE}/SearchBean.action?searchType=basic", params)
    total = extract_total(html)
    return total, html


def extract_total(html: str) -> int:
    """Extract total result count from search results page."""
    m = re.search(r"Celkem\s*:\s*(\d+)", html)
    return int(m.group(1)) if m else 0


def extract_form_params(html: str) -> tuple[str | None, str | None]:
    """Extract _sourcePage and __fp from the paginator form."""
    pag_form = re.search(
        r'action="/vademecum/PaginatorResult\.action">(.*?)</form>',
        html,
        re.DOTALL,
    )
    if not pag_form:
        return None, None
    form_html = pag_form.group(1)
    sp_m = re.search(r'name="_sourcePage"\s+value="([^"]+)"', form_html)
    fp_m = re.search(r'name="__fp"\s+value="([^"]+)"', form_html)
    return (
        sp_m.group(1) if sp_m else None,
        fp_m.group(1) if fp_m else None,
    )


def extract_forward_link(html: str) -> tuple[str, int] | None:
    """Extract the 'next page' link (icon-forward3)."""
    m = re.search(
        r'<a\s+href="(/vademecum/PaginatorResult\.action\?'
        r'_sourcePage=([^&"]+)&(?:amp;)?row=(\d+))">'
        r'\s*<i\s+class="icon-forward3',
        html,
    )
    if m:
        sp = urllib.parse.unquote(htmlmod.unescape(m.group(2)))
        row = int(m.group(3))
        return sp, row
    return None


def goto_page(session: VadeMeCumSession, row: int, sp: str, fp: str) -> str:
    """Navigate to a specific row in paginated results using the form POST."""
    params = {"rowTxt": str(row), "_sourcePage": sp, "__fp": fp}
    return session.post(f"{BASE}/PaginatorResult.action", params)
