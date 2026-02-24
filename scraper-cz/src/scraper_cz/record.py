"""Record detail scraping from VadeMeCum search results and permalink pages.

Ported from enumerate_digi.py -- preserves the exact regex patterns
including the `<aricle` typo in VadeMeCum's HTML.
"""

import re
import logging
import html as htmlmod
import urllib.parse

from .session import VadeMeCumSession, BASE, MRIMAGE_BASE

log = logging.getLogger(__name__)


def parse_page(html: str) -> list[dict]:
    """Extract result entries from a search results page.

    Splits on `<aricle` (the typo in VadeMeCum's HTML, not `<article>`).
    """
    results = []
    blocks = re.split(r'<aricle\s+class="contentRecordList', html)

    for block in blocks[1:]:
        entry = _parse_block(block)
        if entry:
            results.append(entry)
    return results


def _parse_block(block: str) -> dict | None:
    """Parse a single result block from search results."""
    xid_m = re.search(r'xid=([a-f0-9-]{36})', block)
    if not xid_m:
        return None

    title_m = re.search(r'class="title[^"]*"[^>]*tooltip="([^"]*)"', block)
    nad_m = re.search(r'class="cNAD"[^>]*<span[^>]*>(\d+)', block)
    years_m = re.search(r'class="years"[^>]*<span[^>]*tooltip="([^"]*)"', block)
    desc_m = re.search(r"class=\"descr\"[^>]*<span[^>]*tooltip='([^']*)'", block)
    scan_m = re.search(r'tooltip="Zobrazit v plné kvalitě (\d+) sken', block)

    title = htmlmod.unescape(title_m.group(1).strip()) if title_m else ""
    inv_m = re.search(r'inv\.\s*č\.\s*(\d+)', title)
    sig_m = re.search(r'sig\.\s*(\S+)', title)
    fond_m = re.match(r'^(.*?)(?:\s*\u2022)', title)  # bullet: \u2022

    return {
        "xid": xid_m.group(1),
        "title": title,
        "fond": fond_m.group(1).strip() if fond_m else "",
        "inv": int(inv_m.group(1)) if inv_m else None,
        "sig": sig_m.group(1).rstrip(",") if sig_m else "",
        "nad": int(nad_m.group(1)) if nad_m else None,
        "years": years_m.group(1).strip() if years_m else "",
        "desc": htmlmod.unescape(desc_m.group(1).strip()) if desc_m else "",
        "scans": int(scan_m.group(1)) if scan_m else 0,
    }


def load_record_detail(session: VadeMeCumSession, xid: str) -> dict:
    """Fetch the full record permalink page and extract all metadata fields.

    Returns a dict with: xid, title, desc, inv, sig, datace,
    karton_type, karton_number, obsah, rejstrikova_hesla,
    fond_name, nad_number, finding_aid_number, scans, entity_ref.
    """
    html = session.get_permalink_page(xid)
    return parse_record_detail(html, xid)


def parse_record_detail(html: str, xid: str) -> dict:
    """Parse a record detail page into a metadata dict."""
    title_m = re.search(r'<h1[^>]*>(.*?)</h1>', html, re.DOTALL)
    desc_m = re.search(
        r'class="[^"]*popis[^"]*"[^>]*>(.*?)</(?:div|p)>', html, re.DOTALL
    )
    sig_m = re.search(r'(?:Signatura|Sign\.)[^<]*?:\s*([^<]+)', html)
    inv_m = re.search(r'inv\.\s*č\.\s*(\d+)', html)
    date_m = re.search(r'(?:Dat(?:ace|um)|Date)[^<]*?:\s*([^<]+)', html)
    scan_count_m = re.search(r'(\d+)\s*(?:sken|scan|příloh)', html)

    # Additional metadata fields
    karton_type_m = re.search(r'(?:Typ\s+obalu|Druh\s+obalu)[^<]*?:\s*([^<]+)', html)
    karton_num_m = re.search(r'(?:Č(?:íslo)?\s+(?:kartonu|obalu))[^<]*?:\s*([^<]+)', html)
    obsah_m = re.search(r'(?:Obsah|Regest)[^<]*?:\s*([^<]+)', html)
    hesla_m = re.search(r'(?:Rejstříková\s+hesla|Index\s+terms?)[^<]*?:\s*([^<]+)', html)
    fond_m = re.search(r'(?:Fond|Sbírka)[^<]*?:\s*([^<]+)', html)
    nad_m = re.search(r'NAD[^<]*?:\s*(\d+)', html)
    fa_m = re.search(r'(?:Pomůcka|Finding\s+aid)[^<]*?:\s*([^<]+)', html)

    # Entity ref for Zoomify viewer
    entity_ref_m = re.search(r'entityRef=([^&"\']+)', html)

    return {
        "xid": xid,
        "title": _strip_html(title_m.group(1)) if title_m else "",
        "desc": _strip_html(desc_m.group(1)) if desc_m else "",
        "inv": int(inv_m.group(1)) if inv_m else None,
        "sig": sig_m.group(1).strip() if sig_m else "",
        "datace": date_m.group(1).strip() if date_m else "",
        "karton_type": karton_type_m.group(1).strip() if karton_type_m else "",
        "karton_number": karton_num_m.group(1).strip() if karton_num_m else "",
        "obsah": obsah_m.group(1).strip() if obsah_m else "",
        "rejstrikova_hesla": hesla_m.group(1).strip() if hesla_m else "",
        "fond_name": fond_m.group(1).strip() if fond_m else "",
        "nad_number": int(nad_m.group(1)) if nad_m else None,
        "finding_aid_number": fa_m.group(1).strip() if fa_m else "",
        "scans": int(scan_count_m.group(1)) if scan_count_m else 0,
        "entity_ref": (
            urllib.parse.unquote(htmlmod.unescape(entity_ref_m.group(1)))
            if entity_ref_m
            else None
        ),
    }


def extract_entity_ref(html: str) -> str | None:
    """Extract entityRef parameter from a permalink page."""
    m = re.search(r'entityRef=([^&"\']+)', html)
    return urllib.parse.unquote(htmlmod.unescape(m.group(1))) if m else None


def extract_scan_count(html: str) -> int:
    """Extract scan count from a permalink page."""
    m = re.search(r'(\d+)\s*skenů', html)
    return int(m.group(1)) if m else 0


def extract_scan_uuids(html: str) -> list[tuple[str, str]]:
    """Extract (folder, uuid) pairs from Zoomify/media pages."""
    uuids: list[tuple[str, str]] = []
    seen: set[str] = set()
    for m in re.finditer(
        r'images/(\d+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jpg',
        html,
    ):
        folder, uuid = m.group(1), m.group(2)
        if uuid not in seen:
            seen.add(uuid)
            uuids.append((folder, uuid))
    return uuids


def extract_paginator_pages(html: str) -> dict[int, str]:
    """Extract paginator links from media viewer pages."""
    pages: dict[int, str] = {}
    for m in re.finditer(
        r'PaginatorMedia\.action\?_sourcePage=([^&"\']+)&(?:amp;)?row=(\d+)', html,
    ):
        sp = urllib.parse.unquote(htmlmod.unescape(m.group(1)))
        pages[int(m.group(2))] = sp
    return pages


def collect_all_scan_uuids(
    session: VadeMeCumSession,
    entity_ref: str,
    scan_count: int,
    zoom_html: str,
) -> list[tuple[str, str]]:
    """Collect all scan UUIDs by paginating through the media thumbnails.

    Returns list of (folder, uuid) tuples.
    """
    import time
    from .config import get_config

    cfg = get_config()

    all_uuids = extract_scan_uuids(zoom_html)
    paginators = extract_paginator_pages(zoom_html)
    seen = {u for _, u in all_uuids}

    if scan_count <= len(all_uuids):
        return all_uuids

    for row in range(10, scan_count, 10):
        sp = None
        for r in sorted(paginators.keys()):
            sp = paginators[r]
            if r >= row:
                break
        if not sp:
            sp = list(paginators.values())[-1] if paginators else None
        if not sp:
            break

        log.debug("Fetching media page row %d...", row)
        try:
            page_html = session.get_paginator_media_page(sp, row)
            new_uuids = extract_scan_uuids(page_html)
            paginators.update(extract_paginator_pages(page_html))
            added = 0
            for folder, uuid in new_uuids:
                if uuid not in seen:
                    seen.add(uuid)
                    all_uuids.append((folder, uuid))
                    added += 1
            if added:
                log.debug("  +%d UUIDs (total: %d)", added, len(all_uuids))
            time.sleep(cfg.delay * 0.6)
        except Exception as e:
            log.warning("Error fetching media page row %d: %s", row, e)

    return all_uuids


def _strip_html(text: str) -> str:
    """Remove HTML tags from text."""
    return re.sub(r'<[^>]+>', '', text).strip()
