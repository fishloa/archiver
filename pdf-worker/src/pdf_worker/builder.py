"""Build a searchable PDF from scanned page images and OCR text.

Each page becomes a PDF page with the scanned image as background and
invisible OCR text overlaid so the PDF is selectable/searchable.

Pages are processed one at a time to avoid loading all images into memory.
The PDF is written directly to a file on disk.
"""

import io
import logging
from collections.abc import Iterable

from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

log = logging.getLogger(__name__)

# Assume scanned images are 150 DPI for conversion to PDF points (72 dpi).
DPI = 150
SCALE = 72.0 / DPI


def build_searchable_pdf_to_file(pages: Iterable[dict], output_path: str) -> int:
    """Build a searchable PDF, writing directly to a file on disk.

    Args:
        pages: iterable of dicts with keys:
            - image: bytes (JPEG/PNG image data)
            - text: str (OCR text for the page)
            - width: int (image width in pixels)
            - height: int (image height in pixels)
        output_path: path to write the PDF file to.

    Returns:
        Number of pages written.
    """
    c = canvas.Canvas(output_path)
    page_count = 0

    for page in pages:
        page_count += 1
        img_w = page["width"]
        img_h = page["height"]

        pt_w = img_w * SCALE
        pt_h = img_h * SCALE

        c.setPageSize((pt_w, pt_h))

        img_reader = ImageReader(io.BytesIO(page["image"]))
        c.drawImage(img_reader, 0, 0, width=pt_w, height=pt_h)

        text = page.get("text", "")
        if text:
            _draw_invisible_text(c, text, pt_w, pt_h)

        c.showPage()
        log.debug(
            "  Page %d added (%dx%d px -> %.0fx%.0f pt)", page_count, img_w, img_h, pt_w, pt_h
        )

    c.save()
    log.info("PDF built: %d pages -> %s", page_count, output_path)
    return page_count


def _draw_invisible_text(c: canvas.Canvas, text: str, page_w: float, page_h: float):
    """Overlay invisible text lines distributed vertically across the page.

    Uses PDF text rendering mode 3 (invisible) so the text is selectable
    but not visible over the scanned image.
    """
    lines = [line for line in text.split("\n") if line.strip()]
    if not lines:
        return

    font_size = 10
    c.setFont("Helvetica", font_size)

    # Switch to invisible text rendering mode (mode 3)
    c._code.append("3 Tr")

    margin = 10
    usable_h = page_h - 2 * margin

    # Distribute lines evenly across page height (top to bottom)
    if len(lines) == 1:
        line_spacing = usable_h
    else:
        line_spacing = usable_h / (len(lines) - 1) if len(lines) > 1 else usable_h

    for idx, line in enumerate(lines):
        # y from top: first line near top, last line near bottom
        y = page_h - margin - idx * line_spacing
        # Clamp to page bounds
        y = max(margin, min(y, page_h - margin))
        c.drawString(margin, y, line)

    # Reset text rendering mode to normal (mode 0)
    c._code.append("0 Tr")
