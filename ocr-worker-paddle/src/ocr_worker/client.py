"""OCR worker API client — extends the shared ProcessorClient."""

from worker_common import ProcessorClient as _Base


class ProcessorClient(_Base):
    """Adds OCR-specific endpoints to the base client."""

    def __init__(self, base_url: str, token: str):
        super().__init__(base_url, token, user_agent="ocr-worker-paddle/0.1")

    def download_page_image(self, page_id: int) -> bytes:
        """Download the page image as JPEG bytes."""
        resp = self._client.get(f"/api/processor/pages/{page_id}/image")
        resp.raise_for_status()
        return resp.content

    def submit_ocr_result(
        self, page_id: int, engine: str, confidence: float, text_raw: str, hocr: str | None = None
    ):
        """POST OCR results for a page."""
        body = {
            "engine": engine,
            "confidence": confidence,
            "textRaw": text_raw,
            "hocr": hocr,
        }
        resp = self._client.post(f"/api/processor/ocr/{page_id}", json=body)
        resp.raise_for_status()
        return resp.json()
