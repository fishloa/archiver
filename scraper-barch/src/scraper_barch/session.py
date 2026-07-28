"""HTTP session for invenio.bundesarchiv.de.

No authentication is required — every request is a plain unauthenticated
GET. All HTTP calls go through ``ResilientClient`` for automatic retry
with exponential backoff on transient failures.
"""

import logging

from worker_common.http import ResilientClient

from .config import get_config
from .invenio import viewer_url

log = logging.getLogger(__name__)


class InvenioSession:
    """Thin HTTP wrapper around the Bundesarchiv Invenio viewer."""

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            timeout=60.0,
            max_retries=cfg.max_retries,
            retry_backoff=[2, 4, 8, 16, 30],
            headers={"User-Agent": cfg.user_agent},
        )

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def get_viewer_page(self, uuid: str) -> str:
        """Fetch the viewer page HTML for a record UUID."""
        resp = self._client.get(viewer_url(uuid))
        return resp.text

    def download_image(self, url: str) -> bytes:
        """Download a page image. Returns raw bytes."""
        resp = self._client.get(url)
        return resp.content
