"""Backend API client for the OCR worker."""

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
        self._headers = {
            "Authorization": f"Bearer {token}",
            "User-Agent": "ocr-worker-paddle/0.1",
        }
        self._client = httpx.Client(
            base_url=base_url,
            timeout=120.0,
            headers=self._headers,
        )

    def close(self):
        self._client.close()

    def claim_job(self, kind: str = "ocr_page_paddle") -> dict | None:
        """Claim the next pending job. Returns job dict or None."""
        resp = self._client.post("/api/processor/jobs/claim", json={"kind": kind})
        if resp.status_code == 204:
            return None
        resp.raise_for_status()
        return resp.json()

    def download_page_image(self, page_id: int) -> bytes:
        """Download the page image as JPEG bytes."""
        resp = self._client.get(f"/api/processor/pages/{page_id}/image")
        resp.raise_for_status()
        return resp.content

    def submit_ocr_result(
        self, page_id: int, engine: str, confidence: float, text_raw: str, hocr: str | None = None
    ):
        """POST OCR results for a page."""
        body = {
            "engine": engine,
            "confidence": confidence,
            "textRaw": text_raw,
            "hocr": hocr,
        }
        resp = self._client.post(f"/api/processor/ocr/{page_id}", json=body)
        resp.raise_for_status()
        return resp.json()

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
            headers=self._headers,
            timeout=httpx.Timeout(connect=10.0, read=1800.0, write=10.0, pool=10.0),
        ) as sse_client:
            with connect_sse(sse_client, "GET", "/api/processor/jobs/events") as source:
                yield source.iter_sse()
