"""Backend API client for the embedding worker."""

from worker_common import ProcessorClient


class EmbedClient(ProcessorClient):
    """Extends ProcessorClient with embedding-specific endpoints."""

    def __init__(self, base_url: str, token: str):
        super().__init__(base_url, token, user_agent="embed-worker/0.1")

    def get_record_pages(self, record_id: int) -> list[dict]:
        """Fetch all pages with OCR text for a record."""
        resp = self._client.get(f"/api/processor/records/{record_id}/pages")
        resp.raise_for_status()
        return resp.json()

    def get_record_metadata(self, record_id: int) -> dict:
        """Fetch record metadata (title, description, translations)."""
        resp = self._client.get(f"/api/processor/records/{record_id}/metadata")
        resp.raise_for_status()
        return resp.json()

    def store_embeddings(self, record_id: int, chunks: list[dict]):
        """Store text chunks with embeddings in the backend.

        The backend replaces a record's chunks in one atomic delete-then-insert,
        so this has to be a single request — it cannot be split into batches.
        A large record (a 289-page thesis runs to ~500 chunks of 1024 floats)
        is several MB of JSON and hundreds of inserts, which comfortably
        exceeded the old 60s timeout and left the record stuck in `embedding`
        even though the server had stored everything.
        """
        resp = self._client.post(
            "/api/processor/embeddings",
            json={"recordId": record_id, "chunks": chunks},
            timeout=600.0,
        )
        resp.raise_for_status()
        return resp.json()
