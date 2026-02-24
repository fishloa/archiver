"""VadeMeCum session management.

Uses urllib (not httpx) to match the working cookie/session handling
from the original scraper code.
"""

import re
import logging
import urllib.request
import urllib.parse
import http.cookiejar

from .config import get_config

log = logging.getLogger(__name__)

BASE = "https://vademecum.nacr.cz/vademecum"
MRIMAGE_BASE = "https://vademecum.nacr.cz/mrimage/vademecum"


class VadeMeCumSession:
    """Manages an authenticated session with the VadeMeCum portal.

    Handles cookie jar, CSRF-like tokens (_sourcePage, __fp),
    and browser UA spoofing.
    """

    def __init__(self, user_agent: str | None = None):
        cfg = get_config()
        self.ua = user_agent or cfg.user_agent
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
        )
        self._source_page: str | None = None
        self._fp: str | None = None
        self._init_session()

    # -- low-level HTTP ----------------------------------------------------

    def _get(self, url: str, referer: str | None = None) -> bytes:
        """GET request, return raw bytes."""
        req = urllib.request.Request(url)
        req.add_header("User-Agent", self.ua)
        if referer:
            req.add_header("Referer", referer)
        resp = self.opener.open(req, timeout=30)
        return resp.read()

    def get_text(self, url: str, referer: str | None = None) -> str:
        """GET request, return decoded text."""
        return self._get(url, referer).decode("utf-8", errors="replace")

    def _post(self, url: str, params: dict, referer: str | None = None) -> str:
        """POST form-encoded data, return decoded text."""
        data = urllib.parse.urlencode(params).encode("utf-8")
        req = urllib.request.Request(url, data=data)
        req.add_header("User-Agent", self.ua)
        if referer:
            req.add_header("Referer", referer)
        resp = self.opener.open(req, timeout=30)
        return resp.read().decode("utf-8", errors="replace")

    def post(self, url: str, params: dict, referer: str | None = None) -> str:
        """Public POST method."""
        return self._post(url, params, referer)

    def get_bytes(self, url: str, referer: str | None = None) -> bytes | None:
        """GET request returning raw bytes, or None on failure."""
        try:
            return self._get(url, referer)
        except Exception:
            return None

    # -- session initialization --------------------------------------------

    def _init_session(self) -> None:
        """Load the index page to establish a session and extract form tokens."""
        log.info("Initializing VadeMeCum session...")
        html = self.get_text(f"{BASE}/index.jsp")

        sps = re.findall(r'name="_sourcePage"[^>]*value="([^"]+)"', html)
        fps = re.findall(r'name="__fp"[^>]*value="([^"]+)"', html)

        # Use the second form (main search form) when available
        self._source_page = sps[1] if len(sps) > 1 else sps[0]
        self._fp = fps[1] if len(fps) > 1 else fps[0]

        for cookie in self.cookie_jar:
            if cookie.name == "JSESSIONID":
                log.info("Session established: %s...", cookie.value[:16])
                break

    def reinit(self) -> None:
        """Re-initialize the session (fresh tokens)."""
        self._init_session()

    @property
    def source_page(self) -> str:
        assert self._source_page is not None, "Session not initialized"
        return self._source_page

    @property
    def fp(self) -> str:
        assert self._fp is not None, "Session not initialized"
        return self._fp

    # -- VadeMeCum-specific requests ---------------------------------------

    def get_permalink_page(self, xid: str) -> str:
        """Load a record's permalink page."""
        return self.get_text(f"{BASE}/permalink?xid={xid}")

    def get_zoomify_page(self, entity_ref: str, scan_index: int = 0) -> str:
        """Load the Zoomify viewer page for a record."""
        return self._post(
            f"{BASE}/Zoomify.action",
            {"entityRef": entity_ref, "scanIndex": str(scan_index)},
            referer=f"{BASE}/permalink",
        )

    def get_paginator_media_page(self, source_page: str, row: int) -> str:
        """Navigate media paginator to a specific row."""
        params = urllib.parse.urlencode({"_sourcePage": source_page, "row": str(row)})
        return self.get_text(f"{BASE}/PaginatorMedia.action?{params}")

    def download_tile(self, url: str) -> bytes | None:
        """Download a single Zoomify tile. Returns bytes or None."""
        return self.get_bytes(url)
