"""Tests for the streaming PDF builder."""

import os
import tempfile

from PIL import Image
import io

from pdf_worker.builder import build_searchable_pdf_to_file


def _make_page_image(width=300, height=400, color="white"):
    """Create a minimal JPEG image as bytes."""
    img = Image.new("RGB", (width, height), color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_build_single_page():
    """Build a PDF from a single page."""
    pages = [{"image": _make_page_image(), "text": "Hello world", "width": 300, "height": 400}]

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        tmp_path = f.name

    try:
        count = build_searchable_pdf_to_file(pages, tmp_path)
        assert count == 1
        assert os.path.getsize(tmp_path) > 0

        # Verify it's a valid PDF
        with open(tmp_path, "rb") as f:
            header = f.read(5)
        assert header == b"%PDF-"
    finally:
        os.unlink(tmp_path)


def test_build_multiple_pages():
    """Build a PDF from multiple pages."""
    pages = [
        {"image": _make_page_image(200, 300), "text": "Page one", "width": 200, "height": 300},
        {"image": _make_page_image(400, 600), "text": "Page two", "width": 400, "height": 600},
        {"image": _make_page_image(300, 450), "text": "", "width": 300, "height": 450},
    ]

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        tmp_path = f.name

    try:
        count = build_searchable_pdf_to_file(pages, tmp_path)
        assert count == 3
        assert os.path.getsize(tmp_path) > 0
    finally:
        os.unlink(tmp_path)


def test_build_from_iterator():
    """Build a PDF from a generator (streaming mode)."""

    def page_gen():
        for i in range(5):
            yield {
                "image": _make_page_image(200 + i * 10, 300 + i * 10),
                "text": f"Page {i + 1}",
                "width": 200 + i * 10,
                "height": 300 + i * 10,
            }

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        tmp_path = f.name

    try:
        count = build_searchable_pdf_to_file(page_gen(), tmp_path)
        assert count == 5
        assert os.path.getsize(tmp_path) > 0
    finally:
        os.unlink(tmp_path)


def test_build_empty_text():
    """Pages with no OCR text should still produce a valid PDF."""
    pages = [{"image": _make_page_image(), "text": "", "width": 300, "height": 400}]

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        tmp_path = f.name

    try:
        count = build_searchable_pdf_to_file(pages, tmp_path)
        assert count == 1
    finally:
        os.unlink(tmp_path)
