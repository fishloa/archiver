"""Parse detail pages from archivinformationssystem.at."""

import re
import logging
from bs4 import BeautifulSoup

log = logging.getLogger(__name__)

# Map German labels to field names
LABEL_MAP = {
    "Signatur": "signature",
    "Titel": "title",
    "Entstehungszeitraum": "dateRangeText",
    "Stufe": "level",
    "Umfang": "extent",
    "Sprache": "language",
    "Inhalt": "description",
    "Darin": "description",
    "Provenienz": "provenance",
    "Archivalienart": "materialType",
    "Erschließungsgrad": "descriptionLevel",
}


def parse_detail_page(html: str, record_id: str) -> dict:
    """Parse a detail page HTML and extract metadata.

    Returns a dict with:
        - signature, title, dateRangeText, description, level, extent, language
        - digital_objects: list of dicts with veid, deid, sqnznr, klid
        - parent_id, previous_id, next_id
    """
    soup = BeautifulSoup(html, "html.parser")
    result = {
        "signature": "",
        "title": "",
        "dateRangeText": "",
        "description": "",
        "level": "",
        "extent": "",
        "language": "",
        "digital_objects": [],
        "parent_id": None,
        "previous_id": None,
        "next_id": None,
    }

    # Strategy 1: Parse veXDetailAttributLabel / veXDetailAttributValue table cells
    for label_td in soup.find_all("td", class_="veXDetailAttributLabel"):
        label_text = label_td.get_text(strip=True).rstrip(":")
        value_td = label_td.find_next_sibling("td", class_="veXDetailAttributValue")
        if not value_td:
            continue
        value_text = value_td.get_text(strip=True)

        field = LABEL_MAP.get(label_text)
        if field and value_text:
            if field == "description" and result["description"]:
                result["description"] += "\n" + value_text
            else:
                result[field] = value_text

    # Strategy 2: Fallback to og:title meta tag
    if not result["signature"] or not result["title"]:
        og = soup.find("meta", property="og:title")
        if og and og.get("content"):
            content = og["content"]
            # Format: "SIG   Title, Date (Level)"
            parts = content.split("   ", 1)
            if len(parts) == 2:
                if not result["signature"]:
                    result["signature"] = parts[0].strip()
                if not result["title"]:
                    # Strip trailing " (Level)" from title
                    title_part = parts[1]
                    level_match = re.search(r'\(([^)]+)\)\s*$', title_part)
                    if level_match:
                        title_part = title_part[:level_match.start()].strip().rstrip(",")
                    result["title"] = title_part

    # Strategy 3: Fallback to lblTitel span
    if not result["title"]:
        title_span = soup.find("span", id=re.compile(r"lblTitel"))
        if title_span:
            full_text = title_span.get_text(strip=True)
            # Format: "SIG   Title, Date (Level)"
            parts = full_text.split("   ", 1)
            if len(parts) == 2:
                if not result["signature"]:
                    result["signature"] = parts[0].strip()
                title_part = parts[1]
                level_match = re.search(r'\(([^)]+)\)\s*$', title_part)
                if level_match:
                    title_part = title_part[:level_match.start()].strip().rstrip(",")
                result["title"] = title_part

    # Extract digital objects from og:image meta tag and filmstrip
    og_img = soup.find("meta", property="og:image")
    if og_img and og_img.get("content"):
        img_url = og_img["content"]
        # Parse: getimage.aspx?veid=N&deid=N&sqnznr=N&width=N&klid=N
        params = dict(re.findall(r'(\w+)=(\d+)', img_url))
        if "veid" in params and "deid" in params and "sqnznr" in params:
            result["digital_objects"].append({
                "veid": params["veid"],
                "deid": params["deid"],
                "sqnznr": params["sqnznr"],
                "klid": params.get("klid"),
            })

    # Also look for filmstrip images (multiple pages)
    for img in soup.find_all("img", src=re.compile(r"getimage\.aspx")):
        src = img.get("src", "")
        params = dict(re.findall(r'(\w+)=(\d+)', src))
        if "veid" in params and "deid" in params and "sqnznr" in params:
            obj = {
                "veid": params["veid"],
                "deid": params["deid"],
                "sqnznr": params["sqnznr"],
                "klid": params.get("klid"),
            }
            result["digital_objects"].append(obj)

    # Also look for openimage() JavaScript calls
    for match in re.findall(r'openimage\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)', html):
        result["digital_objects"].append({
            "veid": match[0],
            "deid": match[1],
            "sqnznr": match[2],
            "klid": None,
        })

    # Deduplicate digital objects by (veid, sqnznr), preferring higher deid
    # (deid=10 is full-size, deid=0 is thumbnail)
    best = {}
    for obj in result["digital_objects"]:
        key = (obj["veid"], obj["sqnznr"])
        existing = best.get(key)
        if not existing or int(obj.get("deid") or 0) > int(existing.get("deid") or 0):
            best[key] = obj
    result["digital_objects"] = list(best.values())

    # Extract parent from archive plan tree (last parent before current)
    tree_links = soup.select("a.rtIn[href*='detail.aspx?ID=']")
    if tree_links:
        # The second-to-last link is the parent
        for link in tree_links:
            href = link.get("href", "")
            m = re.search(r'ID=(\d+)', href)
            if m and m.group(1) != record_id:
                result["parent_id"] = m.group(1)

    log.debug(
        "Parsed record %s: sig=%s, title=%s, objs=%d",
        record_id, result["signature"], result["title"][:50], len(result["digital_objects"]),
    )

    return result
