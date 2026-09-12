"""Backend ingest API client, shared by every scraper.

This existed as five near-identical copies. They differed in three constants — the source
system, the default archive id and the User-Agent — and in one behaviour: two of them dropped
empty strings from the record body and three did not. Nothing downstream can tell those apart,
because the backend pairs ``IS NOT NULL`` with ``<> ''`` everywhere it asks, so the stricter
version is kept and empty fields are simply not sent.
"""

import json as jsonmod
import logging

import httpx

from .http import ResilientClient

log = logging.getLogger(__name__)


class BackendIngestClient:
    """HTTP client for the archiver backend ingest API.

    Retries with exponential backoff on transient failures, via ``ResilientClient``.

    Subclasses supply what is specific to one archive: ``USER_AGENT`` and
    ``DEFAULT_ARCHIVE_ID``. Everything else is the same call against the same API.
    """

    #: Identifies the scraper in request logs. Subclasses override.
    USER_AGENT = "scraper/0.1"

    #: Archive this scraper ingests into. Must exist in the archives table.
    DEFAULT_ARCHIVE_ID: int | None = None

    def __init__(self, config, base_url: str | None = None, max_retries: int | None = None):
        self._config = config
        url = (base_url or config.require_backend()).rstrip("/")
        retries = max_retries if max_retries is not None else config.max_retries
        self._client = ResilientClient(
            base_url=url,
            timeout=60.0,
            max_retries=retries,
            retry_backoff=[2, 4, 8, 16, 30],
            headers=self._build_headers(config),
        )

    def _build_headers(self, config):
        headers = {"User-Agent": self.USER_AGENT}
        if config.processor_token:
            headers["Authorization"] = f"Bearer {config.processor_token}"
        return headers

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- public API --------------------------------------------------------

    def create_record(self, source_system: str, source_record_id: str, metadata: dict) -> str:
        """Create a new record in the backend. Returns the record_id."""
        cfg = self._config
        body = {
            "archiveId": metadata.get("archive_id", self.DEFAULT_ARCHIVE_ID),
            "sourceSystem": source_system,
            "sourceRecordId": source_record_id,
            "title": metadata.get("title", ""),
            "description": metadata.get("description", ""),
            "dateRangeText": metadata.get("dateRangeText", ""),
            "referenceCode": metadata.get("referenceCode", ""),
            "rawSourceMetadata": jsonmod.dumps(metadata, ensure_ascii=False),
            "lang": cfg.lang,
            "metadataLang": cfg.metadata_lang,
            "sourceUrl": metadata.get("sourceUrl", ""),
        }
        # An absent field is not the same as an empty one. Sending "" writes empty strings into
        # columns that should hold NULL, and every query the backend makes against them already
        # tests for both, so there is nothing to be gained by sending them.
        body = {k: v for k, v in body.items() if v is not None and v != ""}

        resp = self._client.post("/api/ingest/records", json=body)
        data = resp.json()
        record_id = data.get("id") or data.get("record_id")
        log.info("Created record %s for %s/%s", record_id, source_system, source_record_id)
        return record_id

    def upload_page(
        self, record_id: str, seq: int, image_bytes: bytes, metadata: dict | None = None
    ) -> str:
        """Upload a single page image to a record. Returns page id."""
        files = {"image": (f"page_{seq:04d}.jpg", image_bytes, "image/jpeg")}
        params = {"seq": seq}

        if metadata:
            page_meta = {
                key: metadata[key] for key in ("pageLabel", "width", "height") if key in metadata
            }
            if page_meta:
                files["metadata"] = (
                    "metadata.json",
                    jsonmod.dumps(page_meta).encode(),
                    "application/json",
                )

        resp = self._client.post(
            f"/api/ingest/records/{record_id}/pages", files=files, params=params
        )
        page_id = resp.json().get("id")
        log.debug("Uploaded page %d for record %s -> %s", seq, record_id, page_id)
        return page_id

    def upload_pdf(self, record_id: str, pdf_bytes: bytes) -> str:
        """Upload a complete PDF to a record. Returns attachment_id."""
        files = {"pdf": ("document.pdf", pdf_bytes, "application/pdf")}
        resp = self._client.post(f"/api/ingest/records/{record_id}/pdf", files=files)
        attachment_id = resp.json().get("attachmentId")
        log.info("Uploaded PDF for record %s -> %s", record_id, attachment_id)
        return attachment_id

    def complete_ingest(self, record_id: str) -> None:
        """Mark a record as fully ingested."""
        self._client.post(f"/api/ingest/records/{record_id}/complete")
        log.info("Completed ingest for record %s", record_id)

    def delete_record(self, record_id: str) -> None:
        """Delete a record and all its associated data."""
        self._client.delete(f"/api/ingest/records/{record_id}")
        log.info("Deleted record %s", record_id)

    def delete_record_by_source(self, source_system: str, source_record_id: str) -> bool:
        """Delete a record by source system + ID. True if deleted, False if not found."""
        try:
            self._client.delete(f"/api/ingest/records/by-source/{source_system}/{source_record_id}")
            log.info("Deleted record %s/%s", source_system, source_record_id)
            return True
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return False
            raise

    def get_status(self, source_system: str, source_record_id: str) -> dict:
        """Check ingest status. Returns status dict, or empty dict if not found."""
        try:
            resp = self._client.get(f"/api/ingest/status/{source_system}/{source_record_id}")
            return resp.json()
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return {}
            raise

    def get_all_statuses(self, source_system: str) -> dict[str, str]:
        """All record statuses for a source system, as sourceRecordId -> status."""
        resp = self._client.get(f"/api/ingest/status/{source_system}")
        return resp.json()

    def heartbeat(
        self,
        scraper_id: str,
        source_system: str,
        source_name: str,
        records: int = 0,
        pages: int = 0,
    ) -> None:
        """Tell the backend this scraper is alive, so the pipeline page shows it.

        Never fatal: a scrape that is working must not stop because the dashboard cannot be
        updated.
        """
        try:
            self._client.post(
                "/api/ingest/heartbeat",
                json={
                    "scraperId": scraper_id,
                    "sourceSystem": source_system,
                    "sourceName": source_name,
                    "recordsIngested": records,
                    "pagesIngested": pages,
                },
            )
        except Exception:
            log.debug("Heartbeat failed (non-fatal)", exc_info=True)
