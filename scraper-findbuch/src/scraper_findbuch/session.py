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

    def _submit_login_form(self, url: str, html: str) -> str:
        """Find and submit a Contao tl_login form on *html*. Returns response text."""
        cfg = get_config()

        # Locate the <form> that contains a tl_login FORM_SUBMIT
        form_match = re.search(
            r'<form[^>]*>(.*?name="FORM_SUBMIT"\s+value="(tl_login[^"]*)".*?)</form>',
            html,
            re.DOTALL,
        )
        if not form_match:
            return html  # no login form on this page

        form_html = form_match.group(1)
        form_submit = form_match.group(2)

        # Extract hidden fields from within this specific form
        token_match = re.search(r'name="REQUEST_TOKEN"\s+value="([^"]*)"', form_html)
        request_token = token_match.group(1) if token_match else ""

        payload: dict[str, str] = {
            "FORM_SUBMIT": form_submit,
            "REQUEST_TOKEN": request_token,
            "username": cfg.findbuch_username,
            "password": cfg.findbuch_password,
        }
        for field_name in ("_target_path", "_always_use_target_path"):
            m = re.search(rf'name="{field_name}"\s+value="([^"]*)"', form_html)
            if m:
                payload[field_name] = m.group(1)

        resp = self._client.post(url, data=payload)
        return resp.text

    def login(self):
        """Log in to findbuch.at. Required for viewing record details."""
        cfg = get_config()
        if not cfg.findbuch_username or not cfg.findbuch_password:
            raise RuntimeError(
                "FINDBUCH_USERNAME and FINDBUCH_PASSWORD env vars required"
            )

        # Step 1: main site login
        login_page = self._client.get("/loginregistration")
        resp_text = self._submit_login_form("/loginregistration", login_page.text)

        if "logout" not in resp_text.lower() and "abmelden" not in resp_text.lower():
            raise RuntimeError("Login to findbuch.at failed — check credentials")

        self._logged_in = True
        log.info("Successfully logged in to findbuch.at as %s", cfg.findbuch_username)

    def search(self, term, page=1):
        """Search findbuch.at. Returns raw HTML of results page.

        On the first call, detects and submits the search module's own
        Contao login form if results are hidden behind it.
        """
        url = f"/findbuch-search/searchterm/{term}"
        if page > 1:
            url += f"/page/{page}"
        resp = self._client.get(url)
        html = resp.text

        # Contao CMS module-level access: the search results module may
        # show its own login form even if we're already logged in site-wide.
        if "registered users" in html or "registrierten" in html:
            log.info("Search results restricted — submitting module login form...")
            html = self._submit_login_form(url, html)
            if "registered users" in html or "registrierten" in html:
                log.warning("Search results may still be restricted after login")

        return html

    def get_detail(self, detail_url):
        """Fetch a record detail page. Must be logged in. Returns HTML."""
        if not self._logged_in:
            self.login()
        resp = self._client.get(detail_url)
        return resp.text
