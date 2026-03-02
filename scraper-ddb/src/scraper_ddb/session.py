"""HTTP session for Deutsche Digitale Bibliothek / Archivportal-D."""

import logging

from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)

SOLR_URL = "https://api.deutsche-digitale-bibliothek.de/search/index/search/select"
IIIF_BASE = "https://iiif.deutsche-digitale-bibliothek.de"
DDB_ITEM_URL = "https://www.deutsche-digitale-bibliothek.de/item"


class DDBSession:
    """Manages HTTP requests to the Deutsche Digitale Bibliothek APIs.

    Uses the public Solr search endpoint and the IIIF Presentation API.
    All HTTP calls go through ``ResilientClient`` for automatic retry
    with exponential backoff on transient failures.
    """

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            timeout=60.0,
            delay=cfg.delay,
            headers={"User-Agent": cfg.user_agent},
        )

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def search(self, term: str, start: int = 0, rows: int = 20) -> dict:
        """Search the DDB Solr index for archival documents.

        Args:
            term: Full-text search term.
            start: Offset for pagination (default 0).
            rows: Number of results to return (default 20).

        Returns:
            Parsed JSON response dict with 'response' -> 'numFound', 'docs'.
        """
        params = {
            "q": term,
            "wt": "json",
            "fq": [
                "sector_fct:sec_01",  # archive sector
                "type_fct:mediatype_003",  # archival documents
            ],
            "rows": rows,
            "start": start,
            "sort": "score desc",
        }
        resp = self._client.get(SOLR_URL, params=params)
        return resp.json()

    def get_manifest(self, item_id: str) -> dict | None:
        """Fetch the IIIF Presentation manifest for a DDB item.

        Args:
            item_id: The DDB item ID (from Solr 'id' field).

        Returns:
            Parsed JSON manifest dict, or None if unavailable.
        """
        manifest_url = f"{IIIF_BASE}/presentation/2/{item_id}/manifest"
        try:
            resp = self._client.get(manifest_url)
            return resp.json()
        except Exception as e:
            log.warning("Failed to fetch IIIF manifest for %s: %s", item_id, e)
            return None

    def download_image(self, url: str) -> bytes:
        """Download an image from a URL. Returns raw bytes.

        Handles both IIIF image URLs and arbitrary image URLs.
        """
        resp = self._client.get(url)
        return resp.content

    @staticmethod
    def extract_pages_from_manifest(manifest: dict) -> list[dict]:
        """Extract ordered page image URLs from a IIIF manifest.

        IIIF Presentation API 2.x structure:
            manifest -> sequences[0] -> canvases[] -> images[0] -> resource -> @id

        Each canvas image resource @id is already a fully-qualified IIIF
        image URL. We request at max quality: .../full/!2500,2500/0/default.jpg

        Args:
            manifest: Parsed IIIF manifest dict.

        Returns:
            List of dicts: [{"label": str, "image_url": str}, ...]
        """
        pages = []

        sequences = manifest.get("sequences", [])
        if not sequences:
            log.warning("IIIF manifest has no sequences")
            return pages

        canvases = sequences[0].get("canvases", [])
        for i, canvas in enumerate(canvases):
            label = canvas.get("label", f"page_{i + 1}")
            # Normalise label — it may be a dict (multilingual) or a string
            if isinstance(label, dict):
                label = label.get("@value", label.get("en", f"page_{i + 1}"))
            if isinstance(label, list):
                label = label[0] if label else f"page_{i + 1}"

            image_url = None
            images = canvas.get("images", [])
            if images:
                resource = images[0].get("resource", {})
                # resource["@id"] may be a plain URL or a IIIF service URL
                raw_id = resource.get("@id", "")
                # Prefer the IIIF service if present
                service = resource.get("service", {})
                service_id = service.get("@id", "") if isinstance(service, dict) else ""

                if service_id:
                    # Build a max-quality IIIF URL from the service base
                    image_url = (
                        f"{service_id.rstrip('/')}/full/!2500,2500/0/default.jpg"
                    )
                elif raw_id:
                    image_url = raw_id

            if image_url:
                pages.append({"label": str(label), "image_url": image_url})
            else:
                log.warning("Canvas %d has no image URL", i)

        log.info("Extracted %d pages from IIIF manifest", len(pages))
        return pages

    @staticmethod
    def preview_image_url(preview_store: str) -> str | None:
        """Build a IIIF image URL from the Solr preview_store field.

        The preview_store value is like 'binary-<UUID>'. Strip the prefix
        to get the raw UUID used in IIIF paths.

        Args:
            preview_store: Value of the 'preview_store' field from Solr.

        Returns:
            IIIF image URL string, or None if the field is empty.
        """
        if not preview_store:
            return None
        # Strip 'binary-' prefix if present
        uuid = preview_store
        if uuid.startswith("binary-"):
            uuid = uuid[len("binary-") :]
        return f"{IIIF_BASE}/image/2/{uuid}/full/!2500,2500/0/default.jpg"
