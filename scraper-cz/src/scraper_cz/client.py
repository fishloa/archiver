"""Backend API client for the archiver service."""

import time
import logging

import httpx

from .config import get_config

log = logging.getLogger(__name__)

SOURCE_SYSTEM = "vademecum.nacr.cz"


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
            headers={"User-Agent": f"scraper-cz/0.1"},
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
        """Create a new record in the backend. Returns the record_id."""
        resp = self._request(
            "POST",
            "/records",
            json={
                "source_system": source_system,
                "source_record_id": source_record_id,
                "metadata": metadata,
            },
        )
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
        """Upload a single page image to a record. Returns attachment_id."""
        files = {"file": (f"page_{seq:04d}.jpg", image_bytes, "image/jpeg")}
        data = {"seq": str(seq)}
        if metadata:
            import json
            data["metadata"] = json.dumps(metadata)
        resp = self._request(
            "POST",
            f"/records/{record_id}/pages",
            files=files,
            data=data,
        )
        result = resp.json()
        attachment_id = result.get("id") or result.get("attachment_id")
        log.debug("Uploaded page %d for record %s -> %s", seq, record_id, attachment_id)
        return attachment_id

    def upload_pdf(self, record_id: str, pdf_bytes: bytes) -> str:
        """Upload a complete PDF to a record. Returns attachment_id."""
        files = {"file": ("document.pdf", pdf_bytes, "application/pdf")}
        resp = self._request(
            "POST",
            f"/records/{record_id}/pdf",
            files=files,
        )
        result = resp.json()
        attachment_id = result.get("id") or result.get("attachment_id")
        log.info("Uploaded PDF for record %s -> %s", record_id, attachment_id)
        return attachment_id

    def complete_ingest(self, record_id: str) -> None:
        """Mark a record as fully ingested."""
        self._request("POST", f"/records/{record_id}/complete")
        log.info("Completed ingest for record %s", record_id)

    def get_status(self, source_system: str, source_record_id: str) -> dict:
        """Check ingest status. Returns status dict or empty dict if not found."""
        try:
            resp = self._request(
                "GET",
                "/records/status",
                params={
                    "source_system": source_system,
                    "source_record_id": source_record_id,
                },
            )
            return resp.json()
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                return {}
            raise
