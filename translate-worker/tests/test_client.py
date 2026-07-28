"""Regression test: get_page_text/get_record must carry the processor auth header.

Before this fix, both calls went through a separate unauthenticated httpx.Client
("public endpoint" comment, left over from when GET /api/** was permitAll()).
After the archive was locked down to deny anonymous reads by default, every
translate_page job failed with 403 fetching page text -- the pipeline still
reported the record as "complete" because translation failures don't block the
overall record status, so the failure was silent unless job status was checked
directly.
"""

import httpx

from translate_worker.client import ProcessorClient


def _client_with_mock_transport(captured: list[httpx.Request]) -> ProcessorClient:
    client = ProcessorClient(base_url="http://backend:8080", token="test-processor-token")

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        if request.url.path.endswith("/text"):
            return httpx.Response(200, json={"pageId": 1, "text": "hallo", "confidence": 0.9})
        return httpx.Response(200, json={"id": 1, "lang": "de"})

    # Swap the real transport for a mock one, keeping the client's configured
    # headers (this is the exact object get_page_text/get_record must use).
    client._client._transport = httpx.MockTransport(handler)
    return client


def test_get_page_text_sends_bearer_token():
    captured: list[httpx.Request] = []
    client = _client_with_mock_transport(captured)

    result = client.get_page_text(82626)

    assert len(captured) == 1
    assert captured[0].headers["Authorization"] == "Bearer test-processor-token"
    assert captured[0].url.path == "/api/pages/82626/text"
    assert result["text"] == "hallo"


def test_get_record_sends_bearer_token():
    captured: list[httpx.Request] = []
    client = _client_with_mock_transport(captured)

    result = client.get_record(3522)

    assert len(captured) == 1
    assert captured[0].headers["Authorization"] == "Bearer test-processor-token"
    assert captured[0].url.path == "/api/records/3522"
    assert result["lang"] == "de"


def test_no_separate_unauthenticated_client_exists():
    """The old `_public_client` (no auth headers) must not come back."""
    client = ProcessorClient(base_url="http://backend:8080", token="test-processor-token")
    assert not hasattr(client, "_public_client")
