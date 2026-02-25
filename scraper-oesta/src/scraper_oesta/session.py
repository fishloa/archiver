"""HTTP session for archivinformationssystem.at."""

import re
import time
import logging
import httpx
from .config import get_config

log = logging.getLogger(__name__)
BASE_URL = "https://www.archivinformationssystem.at"


class OeStASession:
    """Manages HTTP session with archivinformationssystem.at.

    Handles ASP.NET ViewState extraction and form submission.
    """

    def __init__(self):
        cfg = get_config()
        self._client = httpx.Client(
            base_url=BASE_URL,
            timeout=30.0,
            headers={"User-Agent": cfg.user_agent},
            follow_redirects=True,
        )
        self._delay = cfg.delay

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _throttle(self):
        time.sleep(self._delay)

    def search(self, query):
        """Search via full-text search. Returns list of record IDs found.

        Must handle ASP.NET ViewState:
        1. GET the search page
        2. Extract __VIEWSTATE, __EVENTVALIDATION, __VIEWSTATEGENERATOR
        3. POST the search form with query + hidden fields
        4. Parse results HTML for record IDs
        """
        # Step 1: GET search page
        self._throttle()
        page = self._client.get("/volltextsuche.aspx")
        page.raise_for_status()
        html = page.text

        # Step 2: Extract ASP.NET hidden fields
        viewstate = self._extract_field(html, "__VIEWSTATE")
        validation = self._extract_field(html, "__EVENTVALIDATION")
        generator = self._extract_field(html, "__VIEWSTATEGENERATOR")

        # Step 3: POST search
        self._throttle()
        resp = self._client.post("/volltextsuche.aspx", data={
            "__VIEWSTATE": viewstate,
            "__EVENTVALIDATION": validation,
            "__VIEWSTATEGENERATOR": generator,
            "ctl00$cphMainArea$txtMitAllenWoertern": query,
            "ctl00$cphMainArea$cmdSuchen": "Search",
        })
        resp.raise_for_status()

        # Step 4: Parse result IDs from links like detail.aspx?ID=NNNNN
        ids = re.findall(r'detail\.aspx\?ID=(\d+)', resp.text)
        return list(dict.fromkeys(ids))  # deduplicate preserving order

    def _extract_field(self, html, field_name):
        """Extract ASP.NET ViewState hidden field value."""
        match = re.search(rf'id="{field_name}"\s+value="([^"]*)"', html)
        return match.group(1) if match else ""

    def get_detail(self, record_id):
        """Fetch a record detail page. Returns HTML."""
        self._throttle()
        resp = self._client.get(f"/detail.aspx?ID={record_id}")
        resp.raise_for_status()
        return resp.text

    def get_image(self, veid, deid, sqnznr, width=1200, klid=None):
        """Download an image page. Returns bytes or None if not found."""
        params = {"veid": veid, "deid": deid, "sqnznr": sqnznr, "width": width}
        if klid:
            params["klid"] = klid
        self._throttle()
        try:
            resp = self._client.get("/getimage.aspx", params=params)
            resp.raise_for_status()
            if len(resp.content) < 1000:  # probably an error page, not an image
                return None
            return resp.content
        except httpx.HTTPStatusError:
            return None

    def get_report_pdf(self, record_id):
        """Download the PDF report for a record. Returns bytes."""
        self._throttle()
        resp = self._client.get("/report.aspx", params={"rpt": 1, "id": record_id})
        resp.raise_for_status()
        return resp.content
