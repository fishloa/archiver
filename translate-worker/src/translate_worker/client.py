"""Translate worker API client — extends the shared ProcessorClient."""

import httpx
from worker_common import ProcessorClient as _Base


class ProcessorClient(_Base):
    """Adds translation-specific endpoints to the base client."""

    def __init__(self, base_url: str, token: str):
        super().__init__(base_url, token, user_agent="translate-worker/0.1")
        self._public_client = httpx.Client(
            base_url=base_url,
            timeout=120.0,
            headers={"User-Agent": "translate-worker/0.1"},
        )

    def close(self):
        super().close()
        self._public_client.close()

    def get_page_text(self, page_id: int) -> dict:
        """GET /api/pages/{page_id}/text — public endpoint."""
        resp = self._public_client.get(f"/api/pages/{page_id}/text")
        resp.raise_for_status()
        return resp.json()

    def get_record(self, record_id: int) -> dict:
        """GET /api/records/{record_id} — public endpoint."""
        resp = self._public_client.get(f"/api/records/{record_id}")
        resp.raise_for_status()
        return resp.json()

    def submit_page_translation(self, page_id: int, text_en: str):
        """POST /api/processor/pages/{page_id}/translation."""
        resp = self._client.post(
            f"/api/processor/pages/{page_id}/translation",
            json={"textEn": text_en},
        )
        resp.raise_for_status()

    def submit_record_translation(self, record_id: int, title_en: str, description_en: str):
        """POST /api/processor/records/{record_id}/translation."""
        resp = self._client.post(
            f"/api/processor/records/{record_id}/translation",
            json={"titleEn": title_en, "descriptionEn": description_en},
        )
        resp.raise_for_status()
