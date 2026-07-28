"""URL contract and metadata parsing for the Bundesarchiv Invenio viewer.

Base: https://invenio.bundesarchiv.de/invenio/invenio-viewer/lixe

    viewer page  {base}/view/<uuid>
    image        {base}/files/<path>/<filename>
    whole file   {base}/download/<uuid>                      (zip)
    single page  {base}/download/<uuid>?item=0&page=<N>      (jpg, 0-indexed)

No authentication, session, or cookies are required for any of the above
(verified with plain curl). The viewer page embeds all metadata as a
JavaScript literal (``var data = {...};``) which we extract with a regex
and parse as JSON.

TODO: There is no REST API and no OAI-PMH for the catalogue search side
(both probed, 404) — the catalogue itself is a JSF/PrimeFaces app that
needs session + ViewState POSTs. Implementing signature -> UUID search
would require a headless browser (e.g. Playwright) driving the "Suche
ohne Anmeldung" ("Search without login") button on the login page. This
scraper is deliberately UUID-driven: the operator supplies UUIDs obtained
manually from the Invenio UI.
"""

import json
import re

INVENIO_BASE = "https://invenio.bundesarchiv.de/invenio/invenio-viewer/lixe"

_DATA_RE = re.compile(r"var data = (\{.*?\});\s*\n", re.S)


def viewer_url(uuid: str) -> str:
    """URL of the human-readable viewer page for a record."""
    return f"{INVENIO_BASE}/view/{uuid}"


def parse_viewer_page(html: str) -> dict:
    """Extract the embedded ``var data = {...};`` metadata blob from a viewer page.

    Raises ValueError if the blob cannot be found or parsed.
    """
    m = _DATA_RE.search(html)
    if not m:
        raise ValueError(
            "Could not find 'var data = {...}' literal in viewer page HTML"
        )
    return json.loads(m.group(1))


def get_pages(data: dict) -> list[dict]:
    """Flatten items[].files[] into an ordered list of file dicts.

    Each dict has at least a "filename" key, and typically "thumbnail",
    "width", "height".
    """
    pages: list[dict] = []
    for item in data.get("items") or []:
        pages.extend(item.get("files") or [])
    return pages


def volume_label(data: dict) -> str | None:
    """Return the volume label (e.g. "Bd. 8") if present, else None.

    ``frontend.ve_list`` may be absent or empty for records with no
    volume subdivision.
    """
    frontend = data.get("frontend") or {}
    ve_list = frontend.get("ve_list") or []
    if not ve_list:
        return None
    label = ve_list[0].get("titel")
    return label or None


def build_title(data: dict) -> str:
    """Build the record title: signature, plus volume label if present.

    e.g. "R 43-II/1326" or "R 43-II/1326 (Bd. 8)".
    """
    signature = data.get("title", "")
    label = volume_label(data)
    if label:
        return f"{signature} ({label})"
    return signature


def build_image_url(data: dict, filename: str) -> str:
    """Build the full-size image URL for a given filename within this record."""
    path = data.get("path", "")
    return f"{INVENIO_BASE}/files/{path}/{filename}"


def first_item_page_count(data: dict) -> int:
    """Return len(items[0].files) — used to cross-check a local zip's page count.

    Raises ValueError if the viewer metadata has no items at all.
    """
    items = data.get("items") or []
    if not items:
        raise ValueError("Viewer metadata has no items[] — cannot determine page count")
    return len(items[0].get("files") or [])
