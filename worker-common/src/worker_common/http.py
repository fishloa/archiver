"""Resilient HTTP client with automatic retry and exponential backoff.

Use ``ResilientClient`` as a drop-in replacement for ``httpx.Client``
whenever you need robust handling of transient network errors.

::

    from worker_common.http import ResilientClient

    client = ResilientClient(base_url="https://example.com", delay=0.5)
    resp = client.get("/data")          # retries on 5xx / timeouts
    resp = client.post("/submit", json=body)

Also provides ``wait_for_backend()`` for blocking until an HTTP service
is reachable at startup.
"""

import time
import logging

import httpx

log = logging.getLogger(__name__)

DEFAULT_MAX_RETRIES = 4
DEFAULT_RETRY_BACKOFF = [1, 3, 10, 30]
DEFAULT_TIMEOUT = 30.0


def wait_for_backend(
    url: str,
    *,
    max_wait: int = 300,
    interval: int = 5,
    health_path: str = "/actuator/health",
) -> None:
    """Block until *url* is reachable.

    Polls ``{url}{health_path}`` every *interval* seconds.
    Raises ``RuntimeError`` after *max_wait* seconds.
    """
    endpoint = url.rstrip("/") + health_path
    deadline = time.monotonic() + max_wait
    attempt = 0
    while True:
        attempt += 1
        try:
            resp = httpx.get(endpoint, timeout=5.0)
            if resp.status_code < 500:
                log.info("Backend ready at %s (attempt %d)", url, attempt)
                return
        except Exception:
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Backend not reachable at {url} after {max_wait}s")
        remaining = max(0, int(deadline - time.monotonic()))
        log.info(
            "Waiting for backend at %s (attempt %d, %ds left)…",
            url, attempt, remaining,
        )
        time.sleep(interval)


class ResilientClient:
    """``httpx.Client`` wrapper with automatic retry and backoff.

    * Retries on **5xx**, **429** (rate-limit), transport errors, and timeouts.
    * Does **not** retry on 4xx (except 429) — those are raised immediately.
    * Optional fixed *delay* between every request (polite scraping).

    Parameters
    ----------
    base_url : str, optional
        Prepended to every relative URL.
    timeout : float
        Per-request timeout in seconds (default 30).
    max_retries : int
        Total attempts before giving up (default 4).
    retry_backoff : list[float]
        Sleep durations between retries (default [1, 3, 10, 30]).
    delay : float
        Fixed sleep before each request for polite scraping (default 0).
    headers : dict, optional
        Default headers for every request.
    follow_redirects : bool
        Follow HTTP redirects (default True).
    """

    def __init__(
        self,
        base_url: str | None = None,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
        retry_backoff: list[float] | None = None,
        delay: float = 0.0,
        headers: dict | None = None,
        follow_redirects: bool = True,
    ):
        kwargs: dict = {
            "timeout": timeout,
            "follow_redirects": follow_redirects,
        }
        if base_url:
            kwargs["base_url"] = base_url
        if headers:
            kwargs["headers"] = headers
        self._client = httpx.Client(**kwargs)
        self._max_retries = max_retries
        self._retry_backoff = retry_backoff or list(DEFAULT_RETRY_BACKOFF)
        self._delay = delay

    # -- lifecycle -------------------------------------------------------------

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- cookies ---------------------------------------------------------------

    @property
    def cookies(self):
        """Expose the underlying cookie jar."""
        return self._client.cookies

    # -- core request ----------------------------------------------------------

    def request(self, method: str, url: str, **kwargs) -> httpx.Response:
        """Execute *method* *url* with retry on transient errors."""
        last_exc: Exception | None = None
        for attempt in range(1, self._max_retries + 1):
            if self._delay > 0:
                time.sleep(self._delay)
            try:
                resp = self._client.request(method, url, **kwargs)
                resp.raise_for_status()
                return resp
            except httpx.HTTPStatusError as exc:
                last_exc = exc
                status = exc.response.status_code
                if 400 <= status < 500 and status != 429:
                    raise  # client error — no retry
                if attempt < self._max_retries:
                    wait = self._backoff(attempt)
                    log.warning(
                        "%s %s attempt %d/%d failed (HTTP %d) — retrying in %ds",
                        method, url, attempt, self._max_retries, status, wait,
                    )
                    time.sleep(wait)
            except httpx.TransportError as exc:
                last_exc = exc
                if attempt < self._max_retries:
                    wait = self._backoff(attempt)
                    log.warning(
                        "%s %s attempt %d/%d failed (%s) — retrying in %ds",
                        method, url, attempt, self._max_retries, type(exc).__name__, wait,
                    )
                    time.sleep(wait)
        raise last_exc  # type: ignore[misc]

    def _backoff(self, attempt: int) -> float:
        idx = min(attempt - 1, len(self._retry_backoff) - 1)
        return self._retry_backoff[idx]

    # -- convenience wrappers --------------------------------------------------

    def get(self, url: str, **kwargs) -> httpx.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs) -> httpx.Response:
        return self.request("POST", url, **kwargs)

    def put(self, url: str, **kwargs) -> httpx.Response:
        return self.request("PUT", url, **kwargs)

    def delete(self, url: str, **kwargs) -> httpx.Response:
        return self.request("DELETE", url, **kwargs)
