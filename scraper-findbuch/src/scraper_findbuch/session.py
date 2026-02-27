"""HTTP session for findbuch.at with authentication."""

import re
import logging


from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)
BASE_URL = "https://www.findbuch.at"


class FindbuchSession:
    """Manages an authenticated session with findbuch.at.

    Handles cookies, CSRF tokens (REQUEST_TOKEN), and browser UA spoofing.
    All HTTP calls go through ``ResilientClient`` for automatic retry.
    """

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            base_url=BASE_URL,
            timeout=30.0,
            delay=cfg.delay,
            headers={"User-Agent": cfg.user_agent},
        )
        self._logged_in = False

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def login(self):
        """Log in to findbuch.at. Required for viewing record details."""
        cfg = get_config()
        if not cfg.findbuch_username or not cfg.findbuch_password:
            raise RuntimeError("FINDBUCH_USERNAME and FINDBUCH_PASSWORD env vars required")

        # First GET the login page to get any CSRF tokens
        login_page = self._client.get("/loginregistration")

        # Extract REQUEST_TOKEN if present
        token_match = re.search(r'name="REQUEST_TOKEN"\s+value="([^"]*)"', login_page.text)
        request_token = token_match.group(1) if token_match else ""

        # Extract FORM_SUBMIT value
        form_match = re.search(r'name="FORM_SUBMIT"\s+value="([^"]*)"', login_page.text)
        form_submit = form_match.group(1) if form_match else "tl_login"

        # POST login
        resp = self._client.post("/loginregistration", data={
            "FORM_SUBMIT": form_submit,
            "REQUEST_TOKEN": request_token,
            "username": cfg.findbuch_username,
            "password": cfg.findbuch_password,
        })

        # Check if login succeeded (look for logout link or user indicator)
        if "logout" in resp.text.lower() or "abmelden" in resp.text.lower():
            self._logged_in = True
            log.info("Successfully logged in to findbuch.at as %s", cfg.findbuch_username)
        else:
            raise RuntimeError("Login to findbuch.at failed — check credentials")

    def search(self, term, page=1):
        """Search findbuch.at. Returns raw HTML of results page."""
        url = f"/findbuch-search/searchterm/{term}"
        if page > 1:
            url += f"/page/{page}"
        resp = self._client.get(url)
        return resp.text

    def get_detail(self, detail_url):
        """Fetch a record detail page. Must be logged in. Returns HTML."""
        if not self._logged_in:
            self.login()
        resp = self._client.get(detail_url)
        return resp.text
