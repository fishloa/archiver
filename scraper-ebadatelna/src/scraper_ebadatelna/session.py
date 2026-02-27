"""HTTP session for ebadatelna.cz API."""

import logging


from worker_common.http import ResilientClient

from .config import get_config

log = logging.getLogger(__name__)

BASE_URL = "https://ebadatelna.cz"


class EBadatelnaSession:
    """HTTP session for ebadatelna.cz JSON API.

    Browsing the hierarchy (Item_Read) works without auth.
    Viewing images and OCR search require login + verified account.

    Login: POST /Account/Login {email, password} -> sets session cookie.
    Register at: /Account/Register (requires in-person identity verification).
    """

    def __init__(self):
        cfg = get_config()
        self._client = ResilientClient(
            base_url=BASE_URL,
            timeout=30.0,
            delay=cfg.delay,
            headers={"User-Agent": cfg.user_agent},
        )
        self._authenticated = False

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def login(self):
        """Log in to ebadatelna.cz via AJAX endpoint.

        Required for: viewing images, OCR search, record descriptions.
        Credentials from EBADATELNA_EMAIL / EBADATELNA_PASSWORD env vars.
        """
        cfg = get_config()
        if not cfg.ebadatelna_email or not cfg.ebadatelna_password:
            log.warning("No EBADATELNA_EMAIL/PASSWORD set — browsing only (no images)")
            return False

        # First GET homepage to establish session cookie
        self._client.get("/")

        # AJAX login
        resp = self._client.post("/Account/Login", data={
            "email": cfg.ebadatelna_email,
            "password": cfg.ebadatelna_password,
        })

        # The login returns a result — check if successful
        # On success: returns user active status; on failure: returns error HTML
        body = resp.text.strip()
        if body in ("True", "1", "true"):
            self._authenticated = True
            log.info("Logged in to ebadatelna.cz as %s (verified)", cfg.ebadatelna_email)
            return True
        elif body in ("False", "0", "false"):
            self._authenticated = True
            log.warning("Logged in to ebadatelna.cz but account NOT verified — images may be restricted")
            return True
        else:
            log.error("Login failed: %s", body[:200])
            return False

    @property
    def authenticated(self):
        return self._authenticated

    def item_read(self, parent_id=None, skip=0, take=50, filters=None):
        """Fetch items from /Home/Item_Read.

        Args:
            parent_id: If provided, fetch children of this node.
            skip: Number of items to skip (pagination).
            take: Number of items to return.
            filters: Optional list of filter dicts with keys:
                    {field, operator, value}

        Returns:
            Tuple of (data_list, total_count)
        """
        params = {"skip": skip, "take": take}
        if parent_id:
            params["id"] = parent_id
        if filters:
            for i, f in enumerate(filters):
                params[f"filter[filters][{i}][field]"] = f["field"]
                params[f"filter[filters][{i}][operator]"] = f["operator"]
                params[f"filter[filters][{i}][value]"] = f["value"]
            params["filter[logic]"] = "and"

        resp = self._client.get("/Home/Item_Read", params=params)
        data = resp.json()
        return data.get("Data", []), data.get("Total", 0)

    def ocr_search(self, search_text, skip=0, take=50):
        """Full-text OCR search via /Home/OcrFulltextRead.

        Requires authentication.

        Args:
            search_text: Search query string.
            skip: Number of items to skip (pagination).
            take: Number of items to return.

        Returns:
            Tuple of (data_list, total_count)
        """
        resp = self._client.post("/Home/OcrFulltextRead", data={
            "searchText": search_text,
            "skip": str(skip),
            "take": str(take),
        })
        data = resp.json()
        return data.get("Data", []), data.get("Total", 0)

    def get_signature_images(self, doc_id, page=None, image_type=None):
        """Get image list/thumbnails for a document.

        This is the JS readSignatureImages() equivalent.
        Returns JSON with ThumbnailList, TotalPages, TotalImages, etc.
        Requires authentication.
        """
        params = {"Id": doc_id}
        if page is not None:
            params["page"] = page
        if image_type is not None:
            params["imageType"] = image_type

        resp = self._client.get("/Home/GetSignatureImages", params=params)
        return resp.json()

    def get_image(self, page_path):
        """Download a document page image via /Home/GetImage.

        Args:
            page_path: The page identifier from ThumbnailList.

        Returns:
            Binary image data (bytes).
        """
        resp = self._client.get("/Home/GetImage", params={"page": page_path})
        return resp.content

    def get_thumbnail(self, page_path):
        """Download a thumbnail image.

        Args:
            page_path: The page identifier from ThumbnailList.

        Returns:
            Binary image data (bytes).
        """
        resp = self._client.get("/Home/GetThumbnail", params={"page": page_path})
        return resp.content

    def get_breadcrumbs(self, node_id):
        """Get breadcrumb HTML for a node.

        Args:
            node_id: Node ID to fetch breadcrumbs for.

        Returns:
            HTML string.
        """
        resp = self._client.get("/Home/GetBreadcrumbs", params={"id": node_id})
        return resp.text
