"""Backend API client for the archiver service."""

import json as jsonmod
import logging

import httpx

from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)

SOURCE_SYSTEM = "vademecum.nacr.cz"

# Default archive ID for Czech National Archives.
# Must exist in the archives table.
DEFAULT_ARCHIVE_ID = 1


class BackendClient:
    """HTTP client for the archiver backend API.

    All methods communicate with the backend using ``ResilientClient``,
    which provides automatic retry with exponential backoff on transient
    failures.
    """

    def __init__(self, base_url: str | None = None, max_retries: int | None = None):
        cfg = get_config()
        url = (base_url or cfg.require_backend()).rstrip("/")
        retries = max_retries if max_retries is not None else cfg.max_retries
        self._client = ResilientClient(
            base_url=url,
            timeout=60.0,
            max_retries=retries,
            retry_backoff=[2, 4, 8, 16, 30],
            headers=self._build_headers(cfg),
        )

    @staticmethod
    def _build_headers(cfg):
        headers = {"User-Agent": "scraper-cz/0.1"}
        if cfg.processor_token:
            headers["Authorization"] = f"Bearer {cfg.processor_token}"
        return headers

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

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
            "description": metadata.get("desc") or metadata.get("obsah") or "",
            "dateRangeText": metadata.get("datace", ""),
            "referenceCode": metadata.get("sig", ""),
            "inventoryNumber": str(metadata["inv"]) if metadata.get("inv") else None,
            "containerType": metadata.get("karton_type") or None,
            "containerNumber": metadata.get("karton_number") or None,
            "findingAidNumber": metadata.get("finding_aid_number") or None,
            "indexTerms": metadata.get("rejstrikova_hesla") or None,
            "rawSourceMetadata": jsonmod.dumps(metadata, ensure_ascii=False),
            "lang": cfg.lang,
            "metadataLang": cfg.metadata_lang,
            "sourceUrl": f"https://vademecum.nacr.cz/vademecum/permalink?xid={source_record_id}",
        }
        # Remove None values
        body = {k: v for k, v in body.items() if v is not None}

        resp = self._client.post("/api/ingest/records", json=body)
        data = resp.json()
        record_id = data.get("id") or data.get("record_id")
        log.info(
            "Created record %s for %s/%s", record_id, source_system, source_record_id
        )
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

        resp = self._client.post(
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
        resp = self._client.post(
            f"/api/ingest/records/{record_id}/pdf",
            files=files,
        )
        result = resp.json()
        attachment_id = result.get("attachmentId")
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

    def delete_record_by_source(
        self, source_system: str, source_record_id: str
    ) -> bool:
        """Delete a record by source system + ID. Returns True if deleted."""
        try:
            self._client.delete(
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
            resp = self._client.get(
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
        resp = self._client.get(f"/api/ingest/status/{source_system}")
        return resp.json()

    def repair_record(self, record_id: str) -> dict:
        """Reopen a record for page repair.

        Resets status to 'ingesting' and returns existing page sequences.
        Returns dict with 'id', 'status', 'existingPages'.
        """
        resp = self._client.post(f"/api/ingest/records/{record_id}/repair")
        data = resp.json()
        log.info(
            "Repair record %s — status=%s, existing pages: %s",
            record_id,
            data.get("status"),
            data.get("existingPages"),
        )
        return data

    def heartbeat(
        self,
        scraper_id: str,
        source_system: str,
        source_name: str,
        records: int = 0,
        pages: int = 0,
    ) -> None:
        """Send a heartbeat to the backend so the pipeline page shows this scraper."""
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

    def get_all_records(self, source_system: str) -> list[dict]:
        """Fetch all records for a source system with their full metadata.

        Returns a list of record dicts.
        """
        all_records = []
        page = 0
        while True:
            resp = self._client.get(
                "/api/records",
                params={"page": page, "size": 100},
            )
            data = resp.json()
            content = data.get("content", [])
            # Filter by source system
            for r in content:
                if r.get("sourceSystem") == source_system:
                    all_records.append(r)
            if data.get("last", True):
                break
            page += 1
        return all_records
