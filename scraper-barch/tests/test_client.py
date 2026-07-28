"""Tests for the backend API client."""

import json

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


class TestGetAllStatuses:
    def test_returns_map_keyed_by_source_record_id(self, client, httpx_mock: HTTPXMock):
        """Uses the whole-source-system route — no per-record path segment at
        all, so a signature containing '/' (e.g. 'R 43-II/1326') never
        triggers Spring Security's encoded-slash rejection.
        """
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}",
            method="GET",
            json={"R 43-II/1326": "ocr_pending", "R 43-II/1327": "complete"},
        )
        statuses = client.get_all_statuses(SOURCE_SYSTEM)
        assert statuses["R 43-II/1326"] == "ocr_pending"
        assert statuses["R 43-II/1327"] == "complete"

    def test_returns_empty_map_when_no_records(self, client, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}",
            method="GET",
            json={},
        )
        assert client.get_all_statuses(SOURCE_SYSTEM) == {}

    def test_url_has_no_second_path_segment(self, client, httpx_mock: HTTPXMock):
        """Regression guard: must call .../status/{system} only, never
        .../status/{system}/{anything} (which is what 400s in production).
        """
        httpx_mock.add_response(
            url=f"{BASE}/api/ingest/status/{SOURCE_SYSTEM}",
            method="GET",
            json={},
        )
        client.get_all_statuses(SOURCE_SYSTEM)
        request = httpx_mock.get_requests()[0]
        assert request.url.path == f"/api/ingest/status/{SOURCE_SYSTEM}"


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
