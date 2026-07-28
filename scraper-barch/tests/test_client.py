"""Tests for the backend API client."""

import json
import urllib.parse

import httpx
import pytest
from pytest_httpx import HTTPXMock

from scraper_barch.client import DEFAULT_ARCHIVE_ID, SOURCE_SYSTEM, BackendClient

BASE = "http://test-backend:8000"


@pytest.fixture
def client():
    c = BackendClient(base_url=BASE, max_retries=1)
    yield c
    c.close()


class TestCreateRecord:
    def test_creates_record(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            json={"id": "rec-001"},
        )
        record_id = client.create_record(SOURCE_SYSTEM, "R 43-II/1326", {"title": "x"})
        assert record_id == "rec-001"

    def test_sends_correct_payload(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            json={"id": "rec-002"},
        )
        client.create_record(
            SOURCE_SYSTEM,
            "R 43-II/1326",
            {
                "title": "R 43-II/1326 (Bd. 8)",
                "referenceCode": "R 43-II/1326",
                "sourceUrl": "https://invenio.bundesarchiv.de/x",
                "raw": {"uuid": "abc"},
            },
        )
        request = httpx_mock.get_requests()[0]
        body = json.loads(request.content)
        assert body["sourceSystem"] == SOURCE_SYSTEM
        assert body["sourceRecordId"] == "R 43-II/1326"
        assert body["archiveId"] == DEFAULT_ARCHIVE_ID
        assert body["title"] == "R 43-II/1326 (Bd. 8)"
        assert body["referenceCode"] == "R 43-II/1326"
        assert body["sourceUrl"] == "https://invenio.bundesarchiv.de/x"
        assert body["lang"] == "de"
        assert body["metadataLang"] == "de"
        assert json.loads(body["rawSourceMetadata"]) == {"uuid": "abc"}


class TestUploadPage:
    def test_uploads_page(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL(
                f"{BASE}/api/ingest/records/rec-001/pages", params={"seq": "1"}
            ),
            method="POST",
            json={"id": "att-001"},
        )
        att_id = client.upload_page("rec-001", 1, b"\xff\xd8fake-jpeg")
        assert att_id == "att-001"


class TestCompleteIngest:
    def test_completes(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL(f"{BASE}/api/ingest/records/rec-001/complete"),
            method="POST",
            json={"ok": True},
        )
        client.complete_ingest("rec-001")  # should not raise


class TestGetStatus:
    def test_returns_status(self, client, httpx_mock: HTTPXMock):
        encoded = urllib.parse.quote("R 43-II/1326", safe="")
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}/{encoded}",
            method="GET",
            json={"status": "complete", "id": "rec-001"},
        )
        status = client.get_status(SOURCE_SYSTEM, "R 43-II/1326")
        assert status["status"] == "complete"

    def test_encodes_slash_and_space_in_signature(self, client, httpx_mock: HTTPXMock):
        """Signatures like 'R 43-II/1326' must not leak an extra path segment."""
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}/R%2043-II%2F1326",
            method="GET",
            json={"status": "ocr_pending"},
        )
        status = client.get_status(SOURCE_SYSTEM, "R 43-II/1326")
        assert status["status"] == "ocr_pending"

    def test_returns_empty_on_404(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}/unknown",
            method="GET",
            status_code=404,
        )
        status = client.get_status(SOURCE_SYSTEM, "unknown")
        assert status == {}


class TestRetries:
    def test_retries_on_server_error(self, httpx_mock: HTTPXMock):
        c = BackendClient(base_url=BASE, max_retries=2)
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            status_code=500,
        )
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            json={"id": "rec-retry"},
        )
        record_id = c.create_record(SOURCE_SYSTEM, "xid-retry", {})
        assert record_id == "rec-retry"
        c.close()

    def test_does_not_retry_on_client_error(self, httpx_mock: HTTPXMock):
        c = BackendClient(base_url=BASE, max_retries=3)
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            status_code=400,
            json={"error": "bad request"},
        )
        with pytest.raises(httpx.HTTPStatusError):
            c.create_record(SOURCE_SYSTEM, "bad-xid", {})
        assert len(httpx_mock.get_requests()) == 1
        c.close()
