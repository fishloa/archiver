"""Backend API client for the archiver service."""

import json as jsonmod
import time
import logging

import httpx

from .config import get_config

log = logging.getLogger(__name__)

SOURCE_SYSTEM = "matricula-online.eu"

# Default archive ID for Matricula Online (Vienna records).
# Must exist in the archives table.
DEFAULT_ARCHIVE_ID = 5


class BackendClient:
    """HTTP client for the archiver backend API.

    All methods communicate with the backend using httpx, with
    automatic retries on transient failures.
    """

    def __init__(self, base_url: str | None = None, max_retries: int | None = None):
        cfg = get_config()
        self.base_url = (base_url or cfg.require_backend()).rstrip("/")
        self.max_retries = max_retries if max_retries is not None else cfg.max_retries
        self._client = httpx.Client(
            base_url=self.base_url,
            timeout=60.0,
            headers={"User-Agent": "scraper-matricula/0.1"},
        )

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- internal helpers --------------------------------------------------

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        """Execute a request with retries on transient errors."""
        last_exc = None
        for attempt in range(1, self.max_retries + 1):
            try:
                resp = self._client.request(method, path, **kwargs)
                resp.raise_for_status()
                return resp
            except (httpx.TransportError, httpx.HTTPStatusError) as exc:
                last_exc = exc
                status = getattr(getattr(exc, "response", None), "status_code", None)
                if status and 400 <= status < 500 and status != 429:
                    # Client error (not rate-limit) -- do not retry
                    raise
                wait = min(2 ** attempt, 30)
                log.warning(
                    "Request %s %s attempt %d/%d failed: %s — retrying in %ds",
                    method, path, attempt, self.max_retries, exc, wait,
                )
                time.sleep(wait)
        raise last_exc  # type: ignore[misc]

    # -- public API --------------------------------------------------------

    def create_record(
        self,
        source_system: str,
        source_record_id: str,
        metadata: dict,
    ) -> str:
        """Create a new record in the backend. Returns the record_id.

        Maps scraper metadata fields to IngestRecordRequest fields.
        """
        cfg = get_config()
        body = {
            "archiveId": metadata.get("archive_id", DEFAULT_ARCHIVE_ID),
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
        # Remove None and empty string values
        body = {k: v for k, v in body.items() if v is not None and v != ""}

        resp = self._request("POST", "/api/ingest/records", json=body)
        data = resp.json()
        record_id = data.get("id") or data.get("record_id")
        log.info("Created record %s for %s/%s", record_id, source_system, source_record_id)
        return record_id

    def upload_page(
        self,
        record_id: str,
        seq: int,
        image_bytes: bytes,
        metadata: dict | None = None,
    ) -> str:
        """Upload a single page image to a record. Returns page id."""
        files = {"image": (f"page_{seq:04d}.jpg", image_bytes, "image/jpeg")}
        params = {"seq": seq}

        # PageMetadata is optional (pageLabel, width, height)
        if metadata:
            page_meta = {}
            if "pageLabel" in metadata:
                page_meta["pageLabel"] = metadata["pageLabel"]
            if "width" in metadata:
                page_meta["width"] = metadata["width"]
            if "height" in metadata:
                page_meta["height"] = metadata["height"]
            if page_meta:
                files["metadata"] = (
                    "metadata.json",
                    jsonmod.dumps(page_meta).encode(),
                    "application/json",
                )

        resp = self._request(
            "POST",
            f"/api/ingest/records/{record_id}/pages",
            files=files,
            params=params,
        )
        result = resp.json()
        page_id = result.get("id")
        log.debug("Uploaded page %d for record %s -> %s", seq, record_id, page_id)
        return page_id

    def upload_pdf(self, record_id: str, pdf_bytes: bytes) -> str:
        """Upload a complete PDF to a record. Returns attachment_id."""
        files = {"pdf": ("document.pdf", pdf_bytes, "application/pdf")}
        resp = self._request(
            "POST",
            f"/api/ingest/records/{record_id}/pdf",
            files=files,
        )
        result = resp.json()
        attachment_id = result.get("attachmentId")
        log.info("Uploaded PDF for record %s -> %s", record_id, attachment_id)
        return attachment_id

    def complete_ingest(self, record_id: str) -> None:
        """Mark a record as fully ingested."""
        self._request("POST", f"/api/ingest/records/{record_id}/complete")
        log.info("Completed ingest for record %s", record_id)

    def delete_record(self, record_id: str) -> None:
        """Delete a record and all its associated data."""
        self._request("DELETE", f"/api/ingest/records/{record_id}")
        log.info("Deleted record %s", record_id)

    def delete_record_by_source(self, source_system: str, source_record_id: str) -> bool:
        """Delete a record by source system + ID. Returns True if deleted, False if not found."""
        try:
            self._request(
                "DELETE",
                f"/api/ingest/records/by-source/{source_system}/{source_record_id}",
            )
            log.info("Deleted record %s/%s", source_system, source_record_id)
            return True
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return False
            raise

    def get_status(self, source_system: str, source_record_id: str) -> dict:
        """Check ingest status. Returns status dict or empty dict if not found."""
        try:
            resp = self._request(
                "GET",
                f"/api/ingest/status/{source_system}/{source_record_id}",
            )
            return resp.json()
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return {}
            raise

    def get_all_statuses(self, source_system: str) -> dict[str, str]:
        """Fetch all record statuses for a source system.

        Returns a dict mapping sourceRecordId -> status.
        """
        resp = self._request("GET", f"/api/ingest/status/{source_system}")
        return resp.json()
