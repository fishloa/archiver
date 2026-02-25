"""Backend API client for the translate worker."""

import logging
from contextlib import contextmanager

import httpx
from httpx_sse import connect_sse

log = logging.getLogger(__name__)


class ProcessorClient:
    """Communicates with the archiver backend processor API."""

    def __init__(self, base_url: str, token: str):
        self.base_url = base_url
        self._token = token
        self._auth_headers = {
            "Authorization": f"Bearer {token}",
            "User-Agent": "translate-worker/0.1",
        }
        self._public_headers = {
            "User-Agent": "translate-worker/0.1",
        }
        self._client = httpx.Client(
            base_url=base_url,
            timeout=120.0,
            headers=self._auth_headers,
        )
        self._public_client = httpx.Client(
            base_url=base_url,
            timeout=120.0,
            headers=self._public_headers,
        )

    def close(self):
        self._client.close()
        self._public_client.close()

    def claim_job(self, kind: str) -> dict | None:
        """Claim the next pending job. Returns job dict or None."""
        resp = self._client.post("/api/processor/jobs/claim", json={"kind": kind})
        if resp.status_code == 204:
            return None
        resp.raise_for_status()
        return resp.json()

    def get_page_text(self, page_id: int) -> dict:
        """GET /api/pages/{page_id}/text — public endpoint.

        Returns dict with keys: text, confidence, engine, textEn.
        """
        resp = self._public_client.get(f"/api/pages/{page_id}/text")
        resp.raise_for_status()
        return resp.json()

    def get_record(self, record_id: int) -> dict:
        """GET /api/records/{record_id} — public endpoint.

        Returns record dict with title, description, etc.
        """
        resp = self._public_client.get(f"/api/records/{record_id}")
        resp.raise_for_status()
        return resp.json()

    def submit_page_translation(self, page_id: int, text_en: str):
        """POST /api/processor/pages/{page_id}/translation."""
        resp = self._client.post(
            f"/api/processor/pages/{page_id}/translation",
            json={"textEn": text_en},
        )
        resp.raise_for_status()

    def submit_record_translation(self, record_id: int, title_en: str, description_en: str):
        """POST /api/processor/records/{record_id}/translation."""
        resp = self._client.post(
            f"/api/processor/records/{record_id}/translation",
            json={"titleEn": title_en, "descriptionEn": description_en},
        )
        resp.raise_for_status()

    def complete_job(self, job_id: int, result: str | None = None):
        """Mark a job as completed."""
        body = {"result": result} if result else None
        resp = self._client.post(f"/api/processor/jobs/{job_id}/complete", json=body or {})
        resp.raise_for_status()

    def fail_job(self, job_id: int, error: str):
        """Mark a job as failed."""
        resp = self._client.post(f"/api/processor/jobs/{job_id}/fail", json={"error": error})
        resp.raise_for_status()

    @contextmanager
    def job_events(self):
        """Connect to the SSE job events stream. Yields an iterator of SSE events."""
        with httpx.Client(
            base_url=self.base_url,
            headers=self._auth_headers,
            timeout=httpx.Timeout(connect=10.0, read=1800.0, write=10.0, pool=10.0),
        ) as sse_client:
            with connect_sse(sse_client, "GET", "/api/processor/jobs/events") as source:
                yield source.iter_sse()
