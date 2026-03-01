"""PDF assembly from page images using reportlab."""

import io
import logging

from PIL import Image
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

log = logging.getLogger(__name__)


def build_pdf(images: list[Image.Image]) -> bytes:
    """Build a multi-page PDF from a list of PIL Images.

    Each image becomes one PDF page sized to match the image dimensions
    (in points, at 72 dpi).

    Args:
        images: List of PIL Image objects, one per page.

    Returns:
        PDF file content as bytes.
    """
    if not images:
        raise ValueError("Cannot build PDF from empty image list")

    buf = io.BytesIO()
    c = canvas.Canvas(buf)

    for i, img in enumerate(images):
        w_px, h_px = img.size
        # Convert pixels to points (assume 150 DPI for readable sizing)
        # This gives A4-ish pages for typical archival scan dimensions
        dpi = 150.0
        w_pt = w_px * 72.0 / dpi
        h_pt = h_px * 72.0 / dpi

        c.setPageSize((w_pt, h_pt))

        # Convert PIL image to something reportlab can read
        img_buf = io.BytesIO()
        img.save(img_buf, format="JPEG", quality=90)
        img_buf.seek(0)
        reader = ImageReader(img_buf)

        c.drawImage(reader, 0, 0, width=w_pt, height=h_pt)
        c.showPage()
        log.debug("Added page %d/%d (%dx%d px)", i + 1, len(images), w_px, h_px)

    c.save()
    return buf.getvalue()
