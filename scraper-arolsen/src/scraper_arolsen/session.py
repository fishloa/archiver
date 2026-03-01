"""HTTP session for the Arolsen Archives web service API.

The Arolsen Archives (International Tracing Service) exposes an ASP.NET ASMX
web service at collections-server.arolsen-archives.org. All endpoints are POST
with ``Content-Type: application/json`` body and return JSON with a ``{"d": ...}``
wrapper (a standard ASP.NET ScriptService pattern).

Search is session-based: the server stores queries keyed by (ASP.NET session,
uniqueId).  A fresh session cookie is obtained automatically on first request.

Discovered endpoints (reverse-engineered from Angular bundle):
    BuildQueryGlobalForAngular  — build search query
    GetCount                    — result count for stored query
    GetPersonList               — paginated person results (MUST use orderBy=LastName)
    GetFileByObj                — page images for a document
    GetArchiveInfo              — archive node details by descId
    GetIdbyUrlId                — resolve URL path to descId
    GetFileByParent             — list files under an archive node
    getFileByParentCount        — count files under an archive node
    GetAllValues                — tree children for a parent node
    GetValuesForAngular         — tree children with search filter
    GetNodesIds                 — tree node ID list for a document
"""

import json as _json
import logging
import random
import string

import httpx

from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)

API_BASE_URL = "https://collections-server.arolsen-archives.org"
REFERER = "https://collections.arolsen-archives.org/"
PAGE_SIZE = 1000


def _random_unique_id(length: int = 20) -> str:
    """Generate a random alphanumeric session ID."""
    chars = string.ascii_letters + string.digits
    return "".join(random.choices(chars, k=length))


def _fix_image_url(url: str) -> str:
    """Convert Arolsen's backslash image URLs to proper format.

    The API returns URLs like ``https:\\\\server/path`` — we normalise the
    backslashes to forward slashes.
    """
    return url.replace("\\\\", "//").replace("\\", "/")


class ArolsenSession:
    """Manages interaction with the Arolsen Archives ASMX web service.

    All HTTP calls go through ``ResilientClient`` for automatic retry.
    A random ``uniqueId`` is generated once per session and reused across calls.
    """

    def __init__(self):
        cfg = get_config()
        self._unique_id = _random_unique_id()
        self._client = ResilientClient(
            base_url=API_BASE_URL,
            timeout=30.0,
            delay=cfg.delay,
            headers={
                "User-Agent": cfg.user_agent,
                "Referer": REFERER,
                "Origin": REFERER.rstrip("/"),
                "Content-Type": "application/json",
            },
        )
        log.debug("ArolsenSession initialised with uniqueId=%s", self._unique_id)

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- private helpers -------------------------------------------------------

    def _post(
        self, endpoint: str, *, include_unique_id: bool = True, **params
    ) -> object:
        """POST JSON to an ASMX endpoint. Returns the unwrapped ``d`` value."""
        body = dict(params)
        if include_unique_id:
            body["uniqueId"] = self._unique_id
        resp = self._client.post(
            f"/ITS-WS.asmx/{endpoint}",
            json=body,
        )
        payload = resp.json()
        return payload.get("d")

    # -- search API ------------------------------------------------------------

    def search(self, term: str) -> bool:
        """Start a search query. Returns True if query was built successfully.

        The ASMX service stores the query server-side keyed by
        (ASP.NET session cookie, uniqueId).  Subsequent calls to
        ``get_count`` / ``get_person_list`` reference the stored query
        via the same session+uniqueId pair.

        Args:
            term: Search term (name, keyword, etc.).

        Returns:
            True if the search query was built, False otherwise.
        """
        result = self._post(
            "BuildQueryGlobalForAngular",
            lang="en",
            archiveIds=[],
            strSearch=term,
            synSearch=True,
        )
        log.debug("Search '%s' -> %r", term, result)
        return result is True

    def get_count(self, search_type: str = "person") -> int:
        """Return the total number of results for the stored search.

        Args:
            search_type: Either ``"person"`` or ``"document"``.

        Returns:
            Total result count as an integer.
        """
        result = self._post(
            "GetCount",
            lang="en",
            searchType=search_type,
            useFilter=False,
        )
        try:
            count = int(result)
        except (TypeError, ValueError):
            log.warning("Unexpected GetCount response: %r", result)
            count = 0
        log.debug("GetCount searchType=%s -> %d", search_type, count)
        return count

    def get_person_list(
        self,
        row_num: int = 0,
        order_by: str = "LastName",
        order_type: str = "asc",
    ) -> list[dict]:
        """Fetch one page of person search results.

        IMPORTANT: ``orderBy`` MUST be ``"LastName"`` — all other values
        cause the API to return an empty array.

        Args:
            row_num: 0-based offset into the result set.
            order_by: Sort field — must be ``"LastName"``.
            order_type: Sort direction (``"asc"`` or ``"desc"``).

        Returns:
            List of person dicts with fields: ``ObjId``, ``LastName``,
            ``FirstName``, ``MaidenName``, ``PlaceBirth``, ``Dob``,
            ``DescId``, ``Signature``, etc.
        """
        result = self._post(
            "GetPersonList",
            lang="en",
            rowNum=row_num,
            orderBy=order_by,
            orderType=order_type,
        )
        if isinstance(result, str):
            try:
                result = _json.loads(result)
            except _json.JSONDecodeError:
                result = []
        if not isinstance(result, list):
            log.warning("Unexpected GetPersonList response type: %r", type(result))
            return []
        log.debug("GetPersonList rowNum=%d -> %d results", row_num, len(result))
        return result

    # -- document / archive API ------------------------------------------------

    def get_archive_info(self, desc_id: int, level: int = 1) -> dict:
        """Fetch archive node details by descId.

        Args:
            desc_id: Archive description ID (from person result ``DescId``).
            level: Detail level (default 1).

        Returns:
            Archive info dict with ``Title``, ``TreeData``, ``HeaderItems``, etc.
            Returns empty dict on failure.
        """
        result = self._post(
            "GetArchiveInfo",
            include_unique_id=False,
            descId=str(desc_id),
            level=level,
            lang="en",
        )
        if isinstance(result, str):
            try:
                return _json.loads(result)
            except _json.JSONDecodeError:
                return {}
        if not isinstance(result, dict):
            log.warning(
                "Unexpected GetArchiveInfo response for descId=%s: %r", desc_id, result
            )
            return {}
        return result

    def get_files(self, obj_id: str) -> list[dict]:
        """Fetch the page/image listing for a document.

        Each item in the returned list has ``image`` (full-size URL with
        backslash encoding), ``thmbnl`` (thumbnail), ``title``, ``descId``,
        ``docCounter``, and ``relatedLink``.

        Args:
            obj_id: Document object ID (``ObjId`` from person list).

        Returns:
            List of page dicts.
        """
        result = self._post(
            "GetFileByObj",
            include_unique_id=False,
            objId=str(obj_id),
            lang="en",
        )
        if not isinstance(result, list):
            log.warning(
                "Unexpected GetFileByObj response for objId=%s: %r", obj_id, result
            )
            return []
        return result

    def download_image(self, url: str) -> bytes | None:
        """Download an image by absolute URL.

        The Referer header is required.  The URL is normalised from the
        API's backslash format automatically.

        Args:
            url: Image URL from the ``image`` field (may contain backslashes).

        Returns:
            Raw image bytes, or None on failure.
        """
        url = _fix_image_url(url)
        try:
            resp = self._client.get(url)
            if len(resp.content) < 500:
                log.debug(
                    "Image at %s is too small (%d bytes), skipping",
                    url,
                    len(resp.content),
                )
                return None
            return resp.content
        except httpx.HTTPStatusError as exc:
            log.warning(
                "Failed to download image %s: HTTP %d", url, exc.response.status_code
            )
            return None
        except Exception as exc:
            log.warning("Failed to download image %s: %s", url, exc)
            return None
