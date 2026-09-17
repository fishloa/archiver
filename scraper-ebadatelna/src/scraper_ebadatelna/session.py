"""HTTP session for ebadatelna.cz (Archiv bezpečnostních složek).

The site is an ASP.NET MVC 5 app driven by Kendo UI widgets; there is no public
API and no documentation. Every endpoint and payload below was verified against
the live site on 17 September 2026 — do not "tidy" a parameter name without
re-checking it in the browser, because the server answers a wrong shape with
HTTP 500 and an HTML error page rather than a useful message.

What needs what:

============================  ===========================================
browsing the fond tree        nothing
reading node descriptions     nothing
OCR fulltext search           logged in AND identity-verified account
scan images                   logged in AND identity-verified account
============================  ===========================================

The verification trap: an account that is registered but not yet verified gets
a *successful* login and then **zero results for every OCR query**, `Praha`
included. A nil result on an unverified account is not evidence of absence.
Verification is done in person at Na Struze 3, or electronically through
identitaobcana.cz, and lasts one year.

Authentication is a single cookie, ``RESEARCHER_INFO``, holding the email, a
GUID, a numeric researcher id and an expiry. There is no server-side session, so
the cookie can be handed to curl or another process and keeps working.

Image resolution: ``/Home/GetImage`` is the master — about 992x1402 px for the
"Běžné skeny ve standardním rozlišení" image type. ``/Scan/GetImageSnippet``
accepts arbitrary ``w``/``h`` but merely upscales (measured: Laplacian variance
76.7 at w=3000 against 74.8 for a bicubic upscale of the 992px image), so asking
it for 3000px buys file size and no detail.
"""

import json
import logging
import re

from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)

BASE_URL = "https://ebadatelna.cz"

#: Kendo's own paging cap for ``GetSignatureImages``; the server ignores larger.
SCANS_PER_PAGE = 200

#: ``ImageTypes[].Value`` seen in the wild. 1 is the ordinary scan set; 5 is
#: audio/video, which has no page image and must not be fetched as one.
IMAGE_TYPE_STANDARD = 1
IMAGE_TYPE_MULTIMEDIA = 5

# eb.view.scan.onClickShowScan(event, 'f626290', 28, 1)
_SCAN_CLICK_RE = re.compile(
    r"onClickShowScan\(\s*event\s*,\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"
)
_SNIPPET_SRC_RE = re.compile(r'<img\s+src="([^"]*GetImageSnippet[^"]*)"')
_SCAN_NUM_RE = re.compile(r"Sken číslo:\s*(\d+)")
_TOKEN_INPUT_RE = re.compile(r'name="__RequestVerificationToken"[^>]*value="([^"]+)"')


def normalise_signature_id(node_id: str) -> str:
    """Return the ``f``-prefixed folder id the image endpoints expect.

    The tree (``Item_Read``, ``GetParentPath``) reports folder ids already
    prefixed — ``f626290`` — but OCR search reports the bare number, ``626290``.
    Feeding the bare number to ``GetSignatureImages`` returns HTTP 500.
    """
    node_id = str(node_id)
    if node_id[:1].isdigit():
        return "f" + node_id
    return node_id


class EBadatelnaSession:
    """HTTP session for the ebadatelna.cz endpoints."""

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            base_url=BASE_URL,
            timeout=60.0,
            delay=cfg.delay,
            headers={"User-Agent": cfg.user_agent},
        )
        self._authenticated = False
        self._verified = False
        self._antiforgery: str | None = None

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    @property
    def authenticated(self) -> bool:
        return self._authenticated

    @property
    def verified(self) -> bool:
        """True when the account is identity-verified, so OCR and scans work."""
        return self._verified

    # ------------------------------------------------------------------ auth

    def _ajax_headers(self, **extra) -> dict:
        h = {"X-Requested-With": "XMLHttpRequest"}
        if self._antiforgery:
            h["__RequestVerificationToken"] = self._antiforgery
        h.update(extra)
        return h

    def _fetch_antiforgery(self) -> str | None:
        """Load the homepage for its anti-forgery token and cookie."""
        resp = self._client.get("/")
        match = _TOKEN_INPUT_RE.search(resp.text)
        self._antiforgery = match.group(1) if match else None
        if not self._antiforgery:
            log.warning("No __RequestVerificationToken on the homepage")
        return self._antiforgery

    def login(self) -> bool:
        """Log in with ``EBADATELNA_EMAIL`` / ``EBADATELNA_PASSWORD``.

        ``POST /Account/Login`` takes form fields ``email`` and ``password``
        *and* requires the homepage's anti-forgery token in a
        ``__RequestVerificationToken`` **header** — without it the call is
        rejected. The response body is a bare JSON number: ``1`` for a logged-in
        verified account. Success sets the ``RESEARCHER_INFO`` cookie.
        """
        cfg = get_config()
        if not cfg.ebadatelna_email or not cfg.ebadatelna_password:
            log.warning(
                "No EBADATELNA_EMAIL/PASSWORD set — tree browsing only, no scans"
            )
            return False

        self._fetch_antiforgery()
        resp = self._client.post(
            "/Account/Login",
            data={
                "email": cfg.ebadatelna_email,
                "password": cfg.ebadatelna_password,
            },
            headers=self._ajax_headers(
                **{"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}
            ),
        )
        body = resp.text.strip().strip('"')

        if "RESEARCHER_INFO" not in self._client.cookies:
            log.error("Login failed (no RESEARCHER_INFO cookie): %r", body[:200])
            return False

        self._authenticated = True
        self._verified = body == "1"
        if self._verified:
            log.info("Logged in as %s (verified)", cfg.ebadatelna_email)
        else:
            log.warning(
                "Logged in as %s but the account reports state %r — if this is an "
                "unverified account every OCR search will return zero rows, which "
                "is not evidence of absence",
                cfg.ebadatelna_email,
                body[:40],
            )
        return True

    @property
    def researcher_cookie(self) -> str | None:
        """``RESEARCHER_INFO`` value, for handing the session to another process."""
        return self._client.cookies.get("RESEARCHER_INFO")

    # ------------------------------------------------------------------ tree

    def item_read(self, parent_id: str | None = None) -> tuple[list[dict], int]:
        """Children of one tree node via ``POST /Home/Item_Read``.

        The node id goes in the **query string** (``?id=``) while the body
        carries Kendo's empty ``sort=&group=&filter=``; the server returns every
        child in one response, so there is no paging to do. Omit ``parent_id``
        for the ten top-level fond groups.

        Row fields worth knowing: ``Leaf`` (1 = an inventory unit rather than a
        container), ``HasFiles``, ``FileCount`` (scans), ``HasOcr``,
        ``Signature`` (e.g. ``325-113-1``) and ``ExportNoteMessage`` — the last
        carries the § 37(5) notice when post-1989 scans have been withheld.
        """
        params = {"id": parent_id} if parent_id else None
        resp = self._client.post(
            "/Home/Item_Read",
            params=params,
            data={"sort": "", "group": "", "filter": ""},
            headers=self._ajax_headers(
                **{"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}
            ),
        )
        data = resp.json()
        return data.get("Data", []), data.get("Total", 0)

    def node_info(self, node_id: str) -> dict:
        """Description HTML for a node via ``GET /Home/GetNodeInfo``.

        Returns ``{"description": ..., "signature": ..., "html": ...}``. Worth a
        call per record: the OCR search grid truncates ``Name``, while this
        carries the full archival description — which is where the substance
        often is.
        """
        resp = self._client.get(
            "/Home/GetNodeInfo", params={"id": normalise_signature_id(node_id)}
        )
        html = resp.text
        body = re.sub(r"<br\s*/?>", "\n", html)
        body = re.sub(r"<[^>]+>", "", body)
        lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
        signature = ""
        desc = []
        for ln in lines:
            if ln.lower().startswith("signatura:"):
                signature = ln.split(":", 1)[1].strip()
            else:
                desc.append(ln)
        return {
            "description": "\n".join(desc),
            "signature": signature,
            "html": html,
        }

    def parent_path(self, node_id: str) -> list[dict]:
        """Ancestors of a node via ``GET /Home/GetParentPath`` (JSON, root first)."""
        resp = self._client.get(
            "/Home/GetParentPath", params={"id": normalise_signature_id(node_id)}
        )
        try:
            return resp.json()
        except (ValueError, json.JSONDecodeError):
            return []

    def fond_path(self, node_id: str) -> str:
        """Ancestors as ``"a > b > c"``, for a record's archival context."""
        return " > ".join(
            n.get("Name", "") for n in self.parent_path(node_id) if n.get("Name")
        )

    # ---------------------------------------------------------------- search

    def ocr_search(
        self,
        query: str,
        page: int = 1,
        page_size: int = 200,
        date_from: int = 1885,
        date_to: int = 1993,
        signatures: list[str] | None = None,
        node_filter_ids: list[str] | None = None,
    ) -> tuple[list[dict], int]:
        """OCR fulltext search via ``POST /Home/OcrFulltextRead``.

        Requires a verified account. The date range is mandatory — ``1885`` to
        ``1993`` are the slider's own limits and mean "everything".

        Each row carries ``Id`` (bare folder number), ``Signature``, ``Name``,
        ``TotalNum`` (occurrences in that unit — the ranking key) and
        ``UrlThumbnail`` (an ``<img>`` tag around a ``GetThumbnail`` token).

        Matching is loose and stemmed, and it reads the scan OCR *and* the
        catalogue metadata: ``Černín`` returns 576 units, most of them
        ``Čermín``/``Černý``. Multi-word input is treated as a phrase, so
        ``Felix Czernin`` returns nothing while ``Czernin`` returns 82 — search
        one surname at a time and filter afterwards.
        """
        data = {
            "sort": "",
            "page": page,
            "pageSize": page_size,
            "group": "",
            "filter": "",
            "query": query,
            "dateFrom": date_from,
            "dateTo": date_to,
        }
        for i, sig in enumerate(signatures or []):
            data[f"signatures[{i}]"] = sig
        for i, nid in enumerate(node_filter_ids or []):
            data[f"nodeFilterIds[{i}]"] = nid

        resp = self._client.post(
            "/Home/OcrFulltextRead",
            data=data,
            headers=self._ajax_headers(
                **{"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}
            ),
        )
        payload = resp.json()
        rows = payload.get("Data", [])
        if not rows and not self._verified:
            log.warning(
                "OCR search for %r returned nothing on an unverified account — "
                "this says nothing about the holdings",
                query,
            )
        return rows, payload.get("Total", 0)

    def folder_hits(self, folder_id: str, query: str) -> list[dict]:
        """Which scans inside one unit match a query, and where on the page.

        ``GET /Home/OcrFolderResult`` answers with HTML: one block per hit,
        holding a cropped ``GetImageSnippet`` image, the highlight rectangle and
        ``Sken číslo: N``. This is the only way to go from "this 214-scan volume
        mentions Humprecht 26 times" to "scans 29, 30, 34, 44…".

        Note ``scan_index`` is the 0-based index into the token list from
        :meth:`all_page_tokens`, while ``scan_number`` is the 1-based label the
        site shows; they differ by one.
        """
        resp = self._client.get(
            "/Home/OcrFolderResult",
            params={"folderId": str(folder_id).lstrip("f"), "query": query},
        )
        html = resp.text
        hits = []
        for block in html.split('class="eb-ocr-region-block"')[1:]:
            click = _SCAN_CLICK_RE.search(block)
            snippet = _SNIPPET_SRC_RE.search(block)
            number = _SCAN_NUM_RE.search(block)
            if not click:
                continue
            hits.append(
                {
                    "signature_id": click.group(1),
                    "scan_index": int(click.group(2)),
                    "image_type": int(click.group(3)),
                    "scan_number": int(number.group(1)) if number else None,
                    "snippet_url": BASE_URL + snippet.group(1).replace("&amp;", "&")
                    if snippet
                    else None,
                }
            )
        return hits

    # ---------------------------------------------------------------- images

    def get_signature_images(
        self,
        node_id: str,
        page: int = 1,
        image_type: int = IMAGE_TYPE_STANDARD,
    ) -> dict:
        """Scan tokens for one unit via ``POST /Home/GetSignatureImages``.

        **JSON body**, not query parameters:
        ``{"signatureId": "f626290", "page": 1, "imageType": 1, "sh": true}``.
        A GET, or a POST with ``Id``, returns HTML "500 — Něco se pokazilo".

        Returns ``ScansPerPage`` (200), ``TotalImages``, ``TotalPages``,
        ``SignatureName``, ``SignatureId``, ``ImageTypes`` and
        ``ThumbnailList`` — the per-scan opaque tokens for
        :meth:`get_image`. The tokens are encrypted and short-lived enough that
        they should be fetched immediately rather than stored.
        """
        resp = self._client.post(
            "/Home/GetSignatureImages",
            json={
                "signatureId": normalise_signature_id(node_id),
                "page": page,
                "imageType": image_type,
                "sh": True,
            },
        )
        return resp.json()

    def all_page_tokens(
        self, node_id: str, image_type: int = IMAGE_TYPE_STANDARD
    ) -> tuple[dict, list[str]]:
        """Every scan token for a unit, paging 200 at a time.

        Returns ``(metadata_without_the_token_list, tokens)``.
        """
        first = self.get_signature_images(node_id, page=1, image_type=image_type)
        tokens = list(first.get("ThumbnailList", []))
        for page in range(2, first.get("TotalPages", 1) + 1):
            more = self.get_signature_images(node_id, page=page, image_type=image_type)
            tokens.extend(more.get("ThumbnailList", []))
        meta = {k: v for k, v in first.items() if k != "ThumbnailList"}
        return meta, tokens

    def image_types(self, node_id: str) -> list[dict]:
        """``[{"Name": ..., "Value": 1, "Count": 214}]`` for a unit."""
        return self.get_signature_images(node_id).get("ImageTypes", [])

    def get_image(self, token: str) -> bytes:
        """Full-resolution scan via ``GET /Home/GetImage?page=<token>``."""
        resp = self._client.get("/Home/GetImage", params={"page": token})
        return resp.content

    def get_thumbnail(self, token: str) -> bytes:
        """Thumbnail via ``GET /Home/GetThumbnail?page=<token>`` (about 1 kB)."""
        resp = self._client.get("/Home/GetThumbnail", params={"page": token})
        return resp.content

    def get_breadcrumbs(self, node_id: str) -> str:
        """Breadcrumb HTML. :meth:`fond_path` is usually what you want instead."""
        resp = self._client.get(
            "/Home/GetBreadcrumbs", params={"id": normalise_signature_id(node_id)}
        )
        return resp.text

    @staticmethod
    def record_url(node_id: str) -> str:
        """Deep link a human can open: ``https://ebadatelna.cz/?id=f626290``."""
        return f"{BASE_URL}/?id={normalise_signature_id(node_id)}"
