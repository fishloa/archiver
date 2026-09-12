"""Backend API client for the archiver service.

Every call is inherited from ``worker_common.ingest.BackendIngestClient``; this file holds only what is
specific to Deutsche Digitale Bibliothek.
"""

from worker_common.ingest import BackendIngestClient

from .config import get_config

SOURCE_SYSTEM = "deutsche-digitale-bibliothek.de"

# Default archive ID for Deutsche Digitale Bibliothek.
# Must exist in the archives table.
DEFAULT_ARCHIVE_ID = 9


class BackendClient(BackendIngestClient):
    """HTTP client for the archiver backend API."""

    USER_AGENT = "scraper-ddb/0.1"
    DEFAULT_ARCHIVE_ID = DEFAULT_ARCHIVE_ID

    def __init__(self, base_url: str | None = None, max_retries: int | None = None):
        super().__init__(get_config(), base_url=base_url, max_retries=max_retries)
