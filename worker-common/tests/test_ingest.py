"""The ingest client existed as five near-identical copies, tested in none of them."""

import json

import httpx
import pytest

from worker_common.ingest import BackendIngestClient


class _Config:
    max_retries = 1
    processor_token = "test-token"
    lang = "de"
    metadata_lang = "cs"

    def require_backend(self):
        return "http://backend.test/"


class _Client(BackendIngestClient):
    USER_AGENT = "scraper-test/0.1"
    DEFAULT_ARCHIVE_ID = 7


@pytest.fixture
def client(httpx_mock):
    return _Client(_Config())


def test_subclass_supplies_its_own_archive_and_user_agent(client):
    assert client.DEFAULT_ARCHIVE_ID == 7
    assert client.USER_AGENT == "scraper-test/0.1"


def test_create_record_sends_the_subclass_archive_id(httpx_mock, client):
    httpx_mock.add_response(json={"id": "rec-1"})

    record_id = client.create_record("test.example", "abc", {"title": "A record"})

    assert record_id == "rec-1"
    body = json.loads(httpx_mock.get_requests()[0].content)
    assert body["archiveId"] == 7
    assert body["sourceSystem"] == "test.example"
    assert body["title"] == "A record"


def test_metadata_may_override_the_default_archive(httpx_mock, client):
    httpx_mock.add_response(json={"id": "rec-2"})

    client.create_record("test.example", "abc", {"archive_id": 99, "title": "t"})

    assert json.loads(httpx_mock.get_requests()[0].content)["archiveId"] == 99


def test_empty_fields_are_omitted_rather_than_sent_as_empty_strings(httpx_mock, client):
    # Sending "" writes empty strings into columns that should hold NULL.
    httpx_mock.add_response(json={"id": "rec-3"})

    client.create_record("test.example", "abc", {"title": "t"})

    body = json.loads(httpx_mock.get_requests()[0].content)
    assert "description" not in body
    assert "referenceCode" not in body
    assert "dateRangeText" not in body


def test_language_comes_from_config_not_the_metadata(httpx_mock, client):
    httpx_mock.add_response(json={"id": "rec-4"})

    client.create_record("test.example", "abc", {"title": "t"})

    body = json.loads(httpx_mock.get_requests()[0].content)
    # Content language and catalogue language are set independently and must not be conflated.
    assert body["lang"] == "de"
    assert body["metadataLang"] == "cs"


def test_raw_source_metadata_is_preserved_verbatim(httpx_mock, client):
    httpx_mock.add_response(json={"id": "rec-5"})
    metadata = {"title": "Bořkův", "odd": ["nested", 1]}

    client.create_record("test.example", "abc", metadata)

    body = json.loads(httpx_mock.get_requests()[0].content)
    # Not ASCII-escaped: this is an archive, and the source's own characters are the record.
    assert json.loads(body["rawSourceMetadata"]) == metadata
    assert "Bořkův" in body["rawSourceMetadata"]


def test_authorization_header_is_sent_when_a_token_is_configured(httpx_mock, client):
    httpx_mock.add_response(json={"id": "rec-6"})

    client.create_record("test.example", "abc", {"title": "t"})

    request = httpx_mock.get_requests()[0]
    assert request.headers["Authorization"] == "Bearer test-token"
    assert request.headers["User-Agent"] == "scraper-test/0.1"


def test_delete_by_source_returns_false_for_a_record_that_is_not_there(httpx_mock, client):
    httpx_mock.add_response(status_code=404)

    assert client.delete_record_by_source("test.example", "missing") is False


def test_delete_by_source_raises_on_anything_other_than_a_missing_record(httpx_mock, client):
    httpx_mock.add_response(status_code=500)

    with pytest.raises(httpx.HTTPStatusError):
        client.delete_record_by_source("test.example", "boom")


def test_get_status_returns_empty_for_a_record_that_is_not_there(httpx_mock, client):
    httpx_mock.add_response(status_code=404)

    assert client.get_status("test.example", "missing") == {}


def test_heartbeat_failure_never_stops_a_working_scrape(httpx_mock, client):
    httpx_mock.add_response(status_code=500)

    # No exception: a scrape that is working must not stop because the dashboard cannot update.
    client.heartbeat("scraper-1", "test.example", "Test Source", records=3, pages=9)


def test_page_metadata_only_carries_the_keys_the_backend_accepts(httpx_mock, client):
    httpx_mock.add_response(json={"id": "page-1"})

    client.upload_page(
        "rec-1", 2, b"jpegbytes", {"pageLabel": "2r", "width": 10, "height": 20, "junk": "no"}
    )

    sent = httpx_mock.get_requests()[0].content
    assert b"pageLabel" in sent
    assert b"junk" not in sent
