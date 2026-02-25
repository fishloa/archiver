"""Base backend API client for pipeline workers."""

import logging
from contextlib import contextmanager

import httpx
from httpx_sse import connect_sse

log = logging.getLogger(__name__)


class ProcessorClient:
    """Base client for the archiver backend processor API.

    Provides job lifecycle methods (claim, complete, fail) and SSE
    event streaming. Subclass to add worker-specific API methods.
    """

    def __init__(self, base_url: str, token: str, user_agent: str = "worker/0.1"):
        self.base_url = base_url
        self._token = token
        self._headers = {
            "Authorization": f"Bearer {token}",
            "User-Agent": user_agent,
        }
        self._client = httpx.Client(
            base_url=base_url,
            timeout=120.0,
            headers=self._headers,
        )

    def close(self):
        self._client.close()

    def claim_job(self, kind: str) -> dict | None:
        """Claim the next pending job of the given kind. Returns job dict or None."""
        resp = self._client.post("/api/processor/jobs/claim", json={"kind": kind})
        if resp.status_code == 204:
            return None
        resp.raise_for_status()
        return resp.json()

    def complete_job(self, job_id: int, result: str | None = None):
        """Mark a job as completed."""
        body = {"result": result} if result else {}
        resp = self._client.post(f"/api/processor/jobs/{job_id}/complete", json=body)
        resp.raise_for_status()

    def fail_job(self, job_id: int, error: str):
        """Mark a job as failed."""
        resp = self._client.post(f"/api/processor/jobs/{job_id}/fail", json={"error": error})
        resp.raise_for_status()

    @contextmanager
    def job_events(self, read_timeout: float = 30.0, kinds: list[str] | None = None):
        """Connect to the SSE job events stream. Yields an iterator of SSE events.

        The read_timeout acts as a periodic poll interval -- when it expires,
        the connection closes and the caller can drain pending jobs.

        If kinds is provided, the worker identifies which job types it handles
        so the backend can track connected worker counts per stage.
        """
        params = {}
        if kinds:
            params["kinds"] = kinds
        with httpx.Client(
            base_url=self.base_url,
            headers=self._headers,
            timeout=httpx.Timeout(connect=10.0, read=read_timeout, write=10.0, pool=10.0),
        ) as sse_client:
            with connect_sse(
                sse_client, "GET", "/api/processor/jobs/events", params=params
            ) as source:
                yield source.iter_sse()
