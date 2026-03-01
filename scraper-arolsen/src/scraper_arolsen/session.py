"""HTTP session for the Arolsen Archives web service API.

The Arolsen Archives (International Tracing Service) exposes an ASP.NET ASMX
web service at collections-server.arolsen-archives.org. All endpoints are POST
with ``Content-Type: application/json`` body and return JSON with a ``{"d": ...}``
wrapper (a standard ASP.NET ScriptService pattern).

Authentication is session-less: each client generates a random 20-character
alphanumeric ``uniqueId`` that is passed in every request.
"""

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

    def _post(self, endpoint: str, **params) -> object:
        """POST JSON to an ASMX endpoint. Returns the unwrapped ``d`` value."""
        body = {"uniqueId": self._unique_id, **params}
        resp = self._client.post(
            f"/ITS-WS.asmx/{endpoint}",
            json=body,
        )
        payload = resp.json()
        return payload.get("d")

    # -- public API ------------------------------------------------------------

    def search(self, term: str) -> bool:
        """Start a search query. Returns True if query was built successfully.

        The ASMX service stores the query server-side keyed by uniqueId.
        Subsequent calls to get_count / get_person_list use the same uniqueId
        to reference the stored query.

        Args:
            term: Search term (name, keyword, etc.).

        Returns:
            True if the search query was built, False otherwise.
        """
        result = self._post(
            "BuildQueryGlobalForAngular",
            lang="en",
            term=term,
            mode="and",
            classification="",
            fromYear="",
            toYear="",
            archiveIds=[],
            strSearch=term,
            synSearch=False,
        )
        log.debug("Search '%s' -> %r", term, result)
        return result is True

    def get_count(self, search_type: str = "person") -> int:
        """Return the total number of results for the stored search.

        Args:
            search_type: Either "person" or "document".

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
        self, start_index: int, row_num: int = PAGE_SIZE,
        search_type: str = "person",
        order_by: str = "LastName", order_type: str = "asc",
    ) -> list[dict]:
        """Fetch one page of search results.

        Args:
            start_index: 0-based offset into the result set.
            row_num: Number of results to return (max 1000).
            search_type: Either "person" or "document".
            order_by: Sort field (e.g. "LastName", "relevance").
            order_type: Sort direction ("asc" or "desc").

        Returns:
            List of result dicts, each containing at least ``obj`` and ``title``.
        """
        result = self._post(
            "GetPersonList",
            lang="en",
            startIndex=str(start_index),
            rowNum=str(row_num),
            searchType=search_type,
            orderBy=order_by,
            orderType=order_type,
        )
        if not isinstance(result, list):
            log.warning("Unexpected GetPersonList response type: %r", type(result))
            return []
        log.debug(
            "GetPersonList start=%d rowNum=%d -> %d results",
            start_index, row_num, len(result),
        )
        return result

    def get_metadata(self, obj: str) -> dict:
        """Fetch document metadata by object ID.

        Args:
            obj: Document object ID (the ``obj`` field from GetPersonList).

        Returns:
            Metadata dict with fields such as title, creator, collection, dates, etc.
            Returns an empty dict on failure.
        """
        result = self._post("GetMetaDataByObj", obj=obj, lang="en")
        if not isinstance(result, dict):
            log.warning("Unexpected GetMetaDataByObj response for obj=%s: %r", obj, result)
            return {}
        return result

    def get_files(self, obj: str) -> list[dict]:
        """Fetch the file/page listing for a document.

        Args:
            obj: Document object ID.

        Returns:
            List of file dicts, each containing ``file``, ``folderImage``, etc.
        """
        result = self._post("GetFileByObj", obj=obj, lang="en")
        if not isinstance(result, list):
            log.warning("Unexpected GetFileByObj response for obj=%s: %r", obj, result)
            return []
        return result

    def download_image(self, url: str) -> bytes | None:
        """Download an image by absolute URL.

        The Referer header is required for the image server to serve responses.

        Args:
            url: Absolute image URL from the ``folderImage`` field.

        Returns:
            Raw image bytes, or None if the download fails or the response is too small.
        """
        try:
            resp = self._client.get(url)
            if len(resp.content) < 500:
                log.debug("Image at %s is too small (%d bytes), skipping", url, len(resp.content))
                return None
            return resp.content
        except httpx.HTTPStatusError as exc:
            log.warning("Failed to download image %s: HTTP %d", url, exc.response.status_code)
            return None
        except Exception as exc:
            log.warning("Failed to download image %s: %s", url, exc)
            return None
