"""HTTP session for data.matricula-online.eu."""

import base64
import json
import re
import logging

from bs4 import BeautifulSoup

from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)
BASE_URL = "https://data.matricula-online.eu"
IMG_BASE = "https://img.data.matricula-online.eu"


class MatriculaSession:
    """Manages HTTP requests to Matricula Online.

    All HTTP calls go through ``ResilientClient`` for automatic retry
    with exponential backoff on transient failures.
    """

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            timeout=30.0,
            delay=cfg.delay,
            headers={"User-Agent": cfg.user_agent},
        )

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def get_parish_name(self, parish_url):
        """Get the parish name from the page."""
        resp = self._client.get(parish_url)
        soup = BeautifulSoup(resp.text, "html.parser")
        h1 = soup.find("h1")
        return h1.get_text(strip=True) if h1 else "Unknown Parish"

    def list_parishes(self, diocese_url):
        """List all parishes in a diocese. Returns list of (name, url) tuples."""
        resp = self._client.get(diocese_url)
        soup = BeautifulSoup(resp.text, "html.parser")
        parishes = []

        # Parse the diocese URL to get the path prefix
        # e.g. /en/oesterreich/wien/ -> we want links that extend this path
        from urllib.parse import urlparse

        parsed = urlparse(diocese_url)
        base_path = parsed.path.rstrip("/")

        for link in soup.find_all("a", href=True):
            href = link.get("href", "")
            text = link.get_text(strip=True)
            # Parish links extend the diocese path by one segment
            if href.startswith(base_path + "/") and href != base_path + "/":
                # Check it's one level deeper (parish, not register)
                remainder = href[len(base_path) + 1 :].strip("/")
                if "/" not in remainder and remainder:
                    full_url = BASE_URL + href if not href.startswith("http") else href
                    name = text or remainder
                    parishes.append((name, full_url))

        # Deduplicate
        seen = set()
        unique = []
        for name, url in parishes:
            if url not in seen:
                seen.add(url)
                unique.append((name, url))

        return unique

    def list_registers(self, parish_url):
        """List all registers for a parish from the HTML table.

        Table structure: rows with [checkbox, archival_id, register_type, date_range].
        Register links: /en/oesterreich/wien/{parish}/{register_id}/
        """
        resp = self._client.get(parish_url)
        soup = BeautifulSoup(resp.text, "html.parser")

        # Get parish name
        h1 = soup.find("h1")
        parish_name = h1.get_text(strip=True) if h1 else "Unknown Parish"

        # Parse the parish URL path to identify register links
        from urllib.parse import urlparse

        parsed = urlparse(parish_url)
        parish_path = parsed.path.rstrip("/")

        registers = []
        table = soup.find("table")
        if not table:
            log.warning("No table found on parish page")
            return parish_name, registers

        for row in table.find_all("tr"):
            cells = row.find_all("td")
            if len(cells) < 3:
                continue

            # Find the register link in this row
            link = None
            for a in row.find_all("a", href=True):
                href = a.get("href", "")
                if href.startswith(parish_path + "/") and href != parish_path + "/":
                    link = a
                    break

            if not link:
                continue

            href = link.get("href", "")
            full_url = BASE_URL + href if not href.startswith("http") else href

            # Extract metadata from cells
            archival_id = cells[1].get_text(strip=True) if len(cells) > 1 else ""
            register_type = cells[2].get_text(strip=True) if len(cells) > 2 else ""
            date_range = cells[3].get_text(strip=True) if len(cells) > 3 else ""

            name = f"{register_type} {archival_id}" if archival_id else register_type
            if date_range:
                name += f" ({date_range})"

            registers.append(
                {
                    "name": name,
                    "archival_id": archival_id,
                    "register_type": register_type,
                    "date_range": date_range,
                    "url": full_url,
                }
            )

        log.info("Found %d registers for %s", len(registers), parish_name)
        return parish_name, registers

    def get_register_pages(self, register_url):
        """Fetch a register page and extract all page labels and image URLs.

        Matricula embeds all pages in a JS config object:
            dv1 = new arc.imageview.MatriculaDocView("document", {
                "labels": ["01-Einband_0001", ...],
                "files": ["/image/aHR0cDov...", ...],
                "path": "https://img.data.matricula-online.eu",
                ...
            })

        Returns list of dicts: [{"label": str, "image_url": str}, ...]
        """
        resp = self._client.get(register_url)

        # Extract the JS config object
        match = re.search(
            r'new\s+arc\.imageview\.MatriculaDocView\s*\(\s*"[^"]*"\s*,\s*(\{.*?\})\s*\)',
            resp.text,
            re.DOTALL,
        )
        if not match:
            log.warning("No MatriculaDocView config found on page")
            return []

        config_text = match.group(1)
        # The JS object contains bare identifiers (e.g. "onloaderror": loaderror)
        # that aren't valid JSON. Replace them with null.
        config_text = re.sub(
            r":\s*([a-zA-Z_]\w*)\s*([,}])",
            r": null\2",
            config_text,
        )
        # Also handle "key": true/false/null which are valid JSON
        # The regex above would clobber them — but true/false/null are also
        # valid JS identifiers that happen to be valid JSON too. Since we
        # only need labels/files/path, nulling everything else is fine.

        try:
            config = json.loads(config_text)
        except json.JSONDecodeError as e:
            log.error("Failed to parse MatriculaDocView config: %s", e)
            return []

        labels = config.get("labels", [])
        files = config.get("files", [])
        path_base = config.get("path", IMG_BASE)

        if not files:
            log.warning("No files in MatriculaDocView config")
            return []

        pages = []
        for i, file_path in enumerate(files):
            label = labels[i] if i < len(labels) else f"page_{i + 1}"
            # file_path is like "/image/aHR0cDov..." where the part after /image/
            # is a base64-encoded URL to the actual JPEG on hosted-images.
            # The img proxy returns 403 without proper session cookies, so
            # decode the base64 and hit the origin directly.
            image_url = self._decode_image_path(file_path, path_base)
            pages.append({"label": label, "image_url": image_url})

        log.info("Register has %d pages", len(pages))
        return pages

    @staticmethod
    def _decode_image_path(file_path, path_base):
        """Decode a Matricula image path to a direct URL.

        Paths look like '/image/aHR0cDov...' where the segment after /image/
        is base64-encoded original URL (on hosted-images.matricula-online.eu).
        """
        if file_path.startswith("http"):
            return file_path

        # Strip /image/ prefix to get the base64 part
        b64 = file_path.lstrip("/")
        if b64.startswith("image/"):
            b64 = b64[6:]
        b64 = b64.rstrip("/")

        if not b64:
            return path_base + file_path

        # Add padding if needed
        padding = 4 - len(b64) % 4
        if padding != 4:
            b64 += "=" * padding

        try:
            decoded = base64.b64decode(b64).decode("utf-8")
            if decoded.startswith("http"):
                return decoded
        except Exception:
            pass

        # Fallback: use the proxy URL
        return path_base + file_path

    def download_image(self, image_url):
        """Download an image. Returns bytes."""
        resp = self._client.get(image_url)
        return resp.content
