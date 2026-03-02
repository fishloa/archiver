"""Tests for the backend API client."""

import json

import pytest
import httpx
from pytest_httpx import HTTPXMock

from scraper_cz.client import BackendClient, SOURCE_SYSTEM, DEFAULT_ARCHIVE_ID

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
        record_id = client.create_record(SOURCE_SYSTEM, "xid-123", {"inv": 100})
        assert record_id == "rec-001"

    def test_sends_correct_payload(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/records",
            method="POST",
            json={"id": "rec-002"},
        )
        client.create_record(
            SOURCE_SYSTEM,
            "xid-456",
            {
                "inv": 200,
                "sig": "109-5/1",
                "title": "Test Record",
                "datace": "1920-1930",
            },
        )
        request = httpx_mock.get_requests()[0]
        body = json.loads(request.content)
        assert body["sourceSystem"] == SOURCE_SYSTEM
        assert body["sourceRecordId"] == "xid-456"
        assert body["archiveId"] == DEFAULT_ARCHIVE_ID
        assert body["inventoryNumber"] == "200"
        assert body["referenceCode"] == "109-5/1"
        assert body["title"] == "Test Record"
        assert body["dateRangeText"] == "1920-1930"


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


class TestUploadPdf:
    def test_uploads_pdf(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL(f"{BASE}/api/ingest/records/rec-001/pdf"),
            method="POST",
            json={"attachmentId": "pdf-001"},
        )
        att_id = client.upload_pdf("rec-001", b"%PDF-fake")
        assert att_id == "pdf-001"


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
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}/xid-123",
            method="GET",
            json={"status": "complete", "pages": 5},
        )
        status = client.get_status(SOURCE_SYSTEM, "xid-123")
        assert status["status"] == "complete"

    def test_returns_empty_on_404(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}/unknown-xid",
            method="GET",
            status_code=404,
        )
        status = client.get_status(SOURCE_SYSTEM, "unknown-xid")
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
