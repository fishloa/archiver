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
        """Load the index page to establish a session and extract form tokens.

        VadeMeCum has two search forms:
        - Simple: SearchBean.action (fulltext only)
        - Extended: SearchBean.action?searchType=basic (NAD, digi, levels, etc.)

        We extract tokens for both and pick the right one in search code.
        """
        log.info("Initializing VadeMeCum session...")
        html = self.get_text(f"{BASE}/index.jsp")

        # Extract tokens per form
        forms = re.findall(
            r'<form[^>]*action="([^"]*SearchBean[^"]*)"[^>]*>(.*?)</form>',
            html, re.DOTALL,
        )

        self._simple_sp = None
        self._simple_fp = None
        self._extended_sp = None
        self._extended_fp = None

        for action, form_html in forms:
            sp_m = re.search(r'name="_sourcePage"\s+value="([^"]+)"', form_html)
            fp_m = re.search(r'name="__fp"\s+value="([^"]+)"', form_html)
            if sp_m and fp_m:
                if "searchType=basic" in action or "pevaCNAD" in form_html:
                    self._extended_sp = sp_m.group(1)
                    self._extended_fp = fp_m.group(1)
                else:
                    self._simple_sp = sp_m.group(1)
                    self._simple_fp = fp_m.group(1)

        # Fallback: use whatever we found
        self._source_page = self._extended_sp or self._simple_sp
        self._fp = self._extended_fp or self._simple_fp

        log.info(
            "Forms found: simple=%s, extended=%s",
            "yes" if self._simple_sp else "no",
            "yes" if self._extended_sp else "no",
        )

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

    @property
    def extended_source_page(self) -> str:
        """Token for the extended search form (with NAD/digi filters)."""
        sp = self._extended_sp or self._source_page
        assert sp is not None, "Session not initialized"
        return sp

    @property
    def extended_fp(self) -> str:
        """Token for the extended search form (with NAD/digi filters)."""
        fp = self._extended_fp or self._fp
        assert fp is not None, "Session not initialized"
        return fp

    # -- session expiry detection ------------------------------------------

    @staticmethod
    def is_expired_response(html: str) -> bool:
        """Detect session expiry from a VadeMeCum HTML response.

        The portal redirects to the login/index page when the session
        has timed out.  We check for Czech login markers.
        """
        return (
            "Přihlásit se" in html
            or "sessionExpired" in html
            or ("index.jsp" in html and "JSESSIONID" not in html)
        )

    def get_permalink_page_safe(self, xid: str, retries: int = 2) -> str:
        """Load a permalink page, auto-reinitialising on session expiry.

        Retries up to *retries* times if the response looks like an
        expired-session redirect.
        """
        for attempt in range(retries + 1):
            html = self.get_permalink_page(xid)
            if not self.is_expired_response(html):
                return html
            log.warning(
                "Session expired fetching xid=%s (attempt %d/%d), reinitialising...",
                xid, attempt + 1, retries + 1,
            )
            self.reinit()
        return html  # return last attempt even if still expired

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
