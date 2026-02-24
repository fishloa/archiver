"""Tests for the backend API client."""

import pytest
import httpx
from pytest_httpx import HTTPXMock

from scraper_cz.client import BackendClient, SOURCE_SYSTEM


@pytest.fixture
def client():
    c = BackendClient(base_url="http://test-backend:8000", max_retries=1)
    yield c
    c.close()


class TestCreateRecord:

    def test_creates_record(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url="http://test-backend:8000/records",
            method="POST",
            json={"id": "rec-001"},
        )
        record_id = client.create_record(SOURCE_SYSTEM, "xid-123", {"inv": 100})
        assert record_id == "rec-001"

    def test_sends_correct_payload(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url="http://test-backend:8000/records",
            method="POST",
            json={"id": "rec-002"},
        )
        client.create_record(SOURCE_SYSTEM, "xid-456", {"inv": 200, "sig": "109-5/1"})
        request = httpx_mock.get_requests()[0]
        import json
        body = json.loads(request.content)
        assert body["source_system"] == SOURCE_SYSTEM
        assert body["source_record_id"] == "xid-456"
        assert body["metadata"]["inv"] == 200


class TestUploadPage:

    def test_uploads_page(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL("http://test-backend:8000/records/rec-001/pages"),
            method="POST",
            json={"id": "att-001"},
        )
        att_id = client.upload_page("rec-001", 1, b"\xff\xd8fake-jpeg", {"uuid": "abc"})
        assert att_id == "att-001"


class TestUploadPdf:

    def test_uploads_pdf(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL("http://test-backend:8000/records/rec-001/pdf"),
            method="POST",
            json={"id": "pdf-001"},
        )
        att_id = client.upload_pdf("rec-001", b"%PDF-fake")
        assert att_id == "pdf-001"


class TestCompleteIngest:

    def test_completes(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=httpx.URL("http://test-backend:8000/records/rec-001/complete"),
            method="POST",
            json={"ok": True},
        )
        client.complete_ingest("rec-001")  # should not raise


class TestGetStatus:

    def test_returns_status(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            method="GET",
            json={"status": "complete", "pages": 5},
        )
        status = client.get_status(SOURCE_SYSTEM, "xid-123")
        assert status["status"] == "complete"

    def test_returns_empty_on_404(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            method="GET",
            status_code=404,
        )
        status = client.get_status(SOURCE_SYSTEM, "unknown-xid")
        assert status == {}


class TestRetries:

    def test_retries_on_server_error(self, httpx_mock: HTTPXMock):
        # First call returns 500, second succeeds
        c = BackendClient(base_url="http://test-backend:8000", max_retries=2)
        httpx_mock.add_response(
            url="http://test-backend:8000/records",
            method="POST",
            status_code=500,
        )
        httpx_mock.add_response(
            url="http://test-backend:8000/records",
            method="POST",
            json={"id": "rec-retry"},
        )
        record_id = c.create_record(SOURCE_SYSTEM, "xid-retry", {})
        assert record_id == "rec-retry"
        c.close()

    def test_does_not_retry_on_client_error(self, httpx_mock: HTTPXMock):
        c = BackendClient(base_url="http://test-backend:8000", max_retries=3)
        httpx_mock.add_response(
            url="http://test-backend:8000/records",
            method="POST",
            status_code=400,
            json={"error": "bad request"},
        )
        with pytest.raises(httpx.HTTPStatusError):
            c.create_record(SOURCE_SYSTEM, "bad-xid", {})
        # Should have only made one request (no retries on 400)
        assert len(httpx_mock.get_requests()) == 1
        c.close()
