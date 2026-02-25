"""PDF worker API client — extends the shared ProcessorClient."""

from worker_common import ProcessorClient as _Base


class ProcessorClient(_Base):
    """Adds PDF-specific endpoints to the base client."""

    def __init__(self, base_url: str, token: str):
        super().__init__(base_url, token, user_agent="pdf-worker/0.1")

    def get_record_pages(self, record_id: int) -> list[dict]:
        """Fetch pages with OCR text for a record."""
        resp = self._client.get(f"/api/processor/records/{record_id}/pages")
        resp.raise_for_status()
        return resp.json()

    def download_page_image(self, page_id: int) -> bytes:
        """Download the page image as bytes."""
        resp = self._client.get(f"/api/processor/pages/{page_id}/image")
        resp.raise_for_status()
        return resp.content

    def upload_searchable_pdf(self, record_id: int, pdf_bytes: bytes):
        """Upload the built searchable PDF for a record."""
        resp = self._client.post(
            f"/api/processor/records/{record_id}/searchable-pdf",
            files={"pdf": ("searchable.pdf", pdf_bytes, "application/pdf")},
        )
        resp.raise_for_status()
