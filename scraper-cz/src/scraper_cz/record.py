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
    """Parse a record detail page into a metadata dict.

    VadeMeCum uses <span class="tabularLabel">Label:</span>
    <span class="tabularValue ...">Value</span> pairs for all metadata.
    """
    fields = _extract_tabular_fields(html)

    # Fond/finding aid name from the "contentLine" header area
    fond_name_m = re.search(
        r'Název\s+(?:fondu|pomůcky)\s*:\s*(.*?)(?=\s*Číslo\s+pomůcky|\s*$)',
        _strip_html(html[html.find("contentLine"):html.find("contentLine") + 600])
        if "contentLine" in html else "",
    )

    # Scan count from the thumbnail area
    scan_count_m = re.search(r'(\d+)\s*(?:skenů|sken|scan)', html)

    # Entity ref for Zoomify viewer
    entity_ref_m = re.search(r'entityRef=([^&"\']+)', html)

    inv = fields.get("inv")
    sig = fields.get("sig", "")
    fond = fond_name_m.group(1).strip() if fond_name_m else fields.get("fond_name", "")

    # Build a useful title from fond name, inv, sig
    title_parts = [fond] if fond else []
    if inv:
        title_parts.append(f"inv. {inv}")
    if sig:
        title_parts.append(f"sig. {sig}")
    title = ", ".join(title_parts) or f"Record {xid[:8]}"

    return {
        "xid": xid,
        "title": title,
        "desc": fields.get("obsah", ""),
        "inv": int(inv) if inv and inv.isdigit() else None,
        "sig": sig,
        "datace": fields.get("datace", ""),
        "karton_type": fields.get("karton_type", ""),
        "karton_number": fields.get("karton_number", ""),
        "obsah": fields.get("obsah", ""),
        "rejstrikova_hesla": fields.get("rejstrikova_hesla", ""),
        "fond_name": fond,
        "nad_number": (
            int(fields["nad_number"])
            if fields.get("nad_number", "").isdigit()
            else None
        ),
        "finding_aid_number": fields.get("finding_aid_number", ""),
        "scans": int(scan_count_m.group(1)) if scan_count_m else 0,
        "entity_ref": (
            urllib.parse.unquote(htmlmod.unescape(entity_ref_m.group(1)))
            if entity_ref_m
            else None
        ),
    }


# Map Czech labels to internal field names
_LABEL_MAP = {
    "Signatura": "sig",
    "Sign.": "sig",
    "Inv./přír. číslo": "inv",
    "Inv. číslo": "inv",
    "Datace": "datace",
    "Datum": "datace",
    "Obsah": "obsah",
    "Regest": "obsah",
    "Název fondu": "fond_name",
    "Fond": "fond_name",
    "Sbírka": "fond_name",
    "Číslo listu NAD": "nad_number",
    "NAD": "nad_number",
    "Číslo pomůcky": "finding_aid_number",
    "Pomůcka": "finding_aid_number",
    "Druh ukládací jednotky": "karton_type",
    "Typ obalu": "karton_type",
    "Druh obalu": "karton_type",
    "Ukládací jednotka": "karton_number",
    "Číslo kartonu": "karton_number",
    "Rejstříková hesla": "rejstrikova_hesla",
    "Index terms": "rejstrikova_hesla",
}


def _extract_tabular_fields(html: str) -> dict[str, str]:
    """Extract label/value pairs from VadeMeCum's tabular layout."""
    fields: dict[str, str] = {}
    for m in re.finditer(
        r'<span\s+class="tabularLabel"[^>]*>\s*(.*?)\s*</span>\s*'
        r'<span\s+class="tabularValue[^"]*"[^>]*>\s*(.*?)\s*</span>',
        html,
        re.DOTALL,
    ):
        label = _strip_html(m.group(1)).strip().rstrip(":")
        value = re.sub(r'\s+', ' ', _strip_html(m.group(2))).strip()
        if not value:
            continue
        key = _LABEL_MAP.get(label)
        if key and key not in fields:
            fields[key] = value
    return fields


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
