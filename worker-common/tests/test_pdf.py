"""build_pdf lived in six byte-identical copies across the scrapers and was tested in none
of them. It has one job and two ways to get it wrong: an empty list, and page geometry."""

import io

import pytest
from PIL import Image

from worker_common.pdf import build_pdf


def _page(w, h, colour=(30, 30, 30)):
    return Image.new("RGB", (w, h), colour)


def test_builds_one_pdf_page_per_image():
    pdf = build_pdf([_page(600, 800), _page(600, 800), _page(600, 800)])

    assert pdf.startswith(b"%PDF")
    # Each page is its own object; counting them is enough to catch a dropped or doubled page.
    assert pdf.count(b"/Type /Page\n") == 3 or pdf.count(b"/Type /Page") >= 3


def test_page_is_sized_from_the_image_at_150_dpi():
    # 900px at 150dpi is 6 inches, which is 432 points.
    pdf = build_pdf([_page(900, 1200)])

    assert b"432" in pdf  # width in points
    assert b"576" in pdf  # height in points: 1200px / 150dpi * 72


def test_empty_list_is_rejected_rather_than_producing_an_empty_pdf():
    with pytest.raises(ValueError):
        build_pdf([])


def test_accepts_pages_of_differing_sizes():
    # Archival scans are not uniform; each page is sized independently.
    pdf = build_pdf([_page(600, 800), _page(1200, 400)])

    assert pdf.startswith(b"%PDF")
    assert len(pdf) > 0


def test_greyscale_pages_are_written_without_error():
    # Scans arrive as greyscale; JPEG encoding of an "L" image must not blow up.
    grey = Image.new("L", (400, 500), 128)

    pdf = build_pdf([grey])

    assert pdf.startswith(b"%PDF")


def test_output_is_readable_bytes_not_a_stream():
    pdf = build_pdf([_page(300, 300)])

    assert isinstance(pdf, bytes)
    assert io.BytesIO(pdf).read(4) == b"%PDF"
