"""Tests for embed worker TEI integration."""

import pytest
import respx
import httpx

from embed_worker.main import embed_batch, process_one


class FakeClient:
    """Minimal stub for the EmbedClient used in process_one."""

    def __init__(self):
        self.completed_jobs = []
        self.stored = []
        self._metadata = {}
        self._pages = []

    def get_record_metadata(self, record_id):
        return self._metadata

    def get_record_pages(self, record_id):
        return self._pages

    def store_embeddings(self, record_id, chunks):
        self.stored.append({"recordId": record_id, "chunks": chunks})
        return {"chunksStored": len(chunks)}

    def complete_job(self, job_id):
        self.completed_jobs.append(job_id)


@respx.mock
def test_embed_batch_calls_tei():
    """embed_batch should POST to TEI /embed and return vectors."""
    fake_vectors = [[0.1] * 1024, [0.2] * 1024]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    result = embed_batch("http://tei:80", "test-key", ["hello", "world"])

    assert len(result) == 2
    assert len(result[0]) == 1024
    assert result[0][0] == pytest.approx(0.1)
    assert result[1][0] == pytest.approx(0.2)

    # Verify auth header was sent
    request = respx.calls[0].request
    assert request.headers["authorization"] == "Bearer test-key"


@respx.mock
def test_embed_batch_no_key():
    """embed_batch should work without an API key."""
    fake_vectors = [[0.5] * 1024]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    result = embed_batch("http://tei:80", "", ["test"])

    assert len(result) == 1
    request = respx.calls[0].request
    assert "authorization" not in request.headers


@respx.mock
def test_process_one_prefers_text_raw():
    """process_one should embed text_raw (original OCR) over text_en."""
    fake_vectors = [[0.1] * 1024, [0.2] * 1024]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    client = FakeClient()
    client._metadata = {
        "title": "Konfiskation",
        "title_en": "Confiscation",
        "description": None,
        "description_en": None,
        "reference_code": "AT-001",
    }
    client._pages = [
        {
            "page_id": 1,
            "text_raw": "Originaler deutscher Text über Eigentum",
            "text_en": "English translation about property",
        }
    ]

    job = {"id": 42, "recordId": 100}
    process_one(client, "http://tei:80", "test-key", job)

    assert 42 in client.completed_jobs
    assert len(client.stored) == 1
    chunks = client.stored[0]["chunks"]
    # Should have metadata chunk + page chunk
    assert len(chunks) >= 2

    # The page chunk should contain the original German text (text_raw), not English
    page_chunks = [c for c in chunks if c["pageId"] == 1]
    assert len(page_chunks) >= 1
    assert "Originaler deutscher Text" in page_chunks[0]["content"]


@respx.mock
def test_process_one_falls_back_to_text_en():
    """process_one should fall back to text_en when text_raw is missing."""
    fake_vectors = [[0.1] * 1024, [0.2] * 1024]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    client = FakeClient()
    client._metadata = {
        "title": "Test",
        "title_en": "Test",
        "description": None,
        "description_en": None,
        "reference_code": "REF-1",
    }
    client._pages = [
        {
            "page_id": 2,
            "text_raw": None,
            "text_en": "Fallback English text",
        }
    ]

    job = {"id": 43, "recordId": 101}
    process_one(client, "http://tei:80", "test-key", job)

    assert 43 in client.completed_jobs
    chunks = client.stored[0]["chunks"]
    page_chunks = [c for c in chunks if c["pageId"] == 2]
    assert len(page_chunks) >= 1
    assert "Fallback English text" in page_chunks[0]["content"]


@respx.mock
def test_process_one_skips_empty_pages():
    """process_one should skip pages with no text at all."""
    fake_vectors = [[0.1] * 1024]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    client = FakeClient()
    client._metadata = {
        "title": "Test",
        "title_en": "Test",
        "description": None,
        "description_en": None,
        "reference_code": "REF-2",
    }
    client._pages = [
        {"page_id": 3, "text_raw": None, "text_en": None},
        {"page_id": 4, "text_raw": "", "text_en": ""},
    ]

    job = {"id": 44, "recordId": 102}
    process_one(client, "http://tei:80", "test-key", job)

    # Job still completes (metadata chunk exists)
    assert 44 in client.completed_jobs
    chunks = client.stored[0]["chunks"]
    # Only metadata chunk, no page chunks
    page_chunks = [c for c in chunks if c["pageId"] is not None]
    assert len(page_chunks) == 0


@respx.mock
def test_process_one_1024_dim_vectors():
    """Verify stored embeddings are 1024-dimensional (BGE-M3)."""
    fake_vectors = [[float(i) / 1024 for i in range(1024)]]

    respx.post("http://tei:80/embed").mock(
        return_value=httpx.Response(200, json=fake_vectors)
    )

    client = FakeClient()
    client._metadata = {
        "title": "Dim check",
        "title_en": None,
        "description": None,
        "description_en": None,
        "reference_code": "",
    }
    client._pages = []

    job = {"id": 45, "recordId": 103}
    process_one(client, "http://tei:80", "test-key", job)

    chunks = client.stored[0]["chunks"]
    assert len(chunks) == 1
    assert len(chunks[0]["embedding"]) == 1024
