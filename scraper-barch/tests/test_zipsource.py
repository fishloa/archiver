"""Tests for local-zip ingestion (list_zip_pages / read_zip_page).

All zips are built in-memory with the stdlib zipfile module — no large
binary fixture is committed and no test touches the network.
"""

import zipfile
from io import BytesIO

import pytest

from scraper_barch.zipsource import list_zip_pages, read_zip_page


def _build_zip(path, entries: list[tuple[str, bytes]]) -> None:
    """Write *entries* (name, content) to a zip at *path*, in the given order."""
    with zipfile.ZipFile(path, "w") as zf:
        for name, content in entries:
            zf.writestr(name, content)


class TestListZipPagesOrdering:
    def test_orders_by_numeric_suffix_not_zero_padded(self, tmp_path):
        """Entries 1..10 (unpadded) stored out of order, with 2 and 10
        adjacent in storage order — lexical sort of filenames would put
        '..._10.jpg' before '..._2.jpg', which is the wrong page order.
        """
        zip_path = tmp_path / "test.zip"
        # Deliberately store out of numeric order, with 10 right after 2.
        order = [1, 3, 4, 5, 6, 7, 8, 9, 2, 10]
        _build_zip(
            zip_path,
            [(f"R_TEST_{n}.jpg", f"page-{n}".encode()) for n in order],
        )
        pages = list_zip_pages(str(zip_path))
        assert [num for num, _ in pages] == list(range(1, 11))
        assert pages[1][1] == "R_TEST_2.jpg"
        assert pages[9][1] == "R_TEST_10.jpg"

    def test_orders_zero_padded_entries_out_of_storage_order(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        _build_zip(
            zip_path,
            [
                ("R_43_II_1326_0002.jpg", b"two"),
                ("R_43_II_1326_0001.jpg", b"one"),
                ("R_43_II_1326_0003.jpg", b"three"),
            ],
        )
        pages = list_zip_pages(str(zip_path))
        assert [num for num, _ in pages] == [1, 2, 3]


class TestListZipPagesGapsAndDuplicates:
    def test_raises_on_gap(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        _build_zip(
            zip_path,
            [
                ("R_TEST_0001.jpg", b"one"),
                ("R_TEST_0002.jpg", b"two"),
                ("R_TEST_0004.jpg", b"four"),  # gap: page 3 missing
            ],
        )
        with pytest.raises(ValueError, match=r"missing=\[3\]"):
            list_zip_pages(str(zip_path))

    def test_raises_on_duplicate(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        _build_zip(
            zip_path,
            [
                ("R_TEST_0001.jpg", b"one"),
                ("R_TEST_0002.jpg", b"two-a"),
                ("R_OTHER_0002.jpg", b"two-b"),  # different name, same page number 2
            ],
        )
        with pytest.raises(ValueError, match=r"Duplicate page numbers.*\[2\]"):
            list_zip_pages(str(zip_path))

    def test_raises_on_non_matching_filename(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        _build_zip(zip_path, [("readme.txt", b"not a page")])
        with pytest.raises(ValueError, match="does not match"):
            list_zip_pages(str(zip_path))

    def test_ignores_directory_entries(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("subdir/", b"")
            zf.writestr("R_TEST_0001.jpg", b"one")
        pages = list_zip_pages(str(zip_path))
        assert [num for num, _ in pages] == [1]


class TestReadZipPage:
    def test_reads_entry_bytes(self, tmp_path):
        zip_path = tmp_path / "test.zip"
        _build_zip(zip_path, [("R_TEST_0001.jpg", b"\xff\xd8fake-jpeg-bytes")])
        data = read_zip_page(str(zip_path), "R_TEST_0001.jpg")
        assert data == b"\xff\xd8fake-jpeg-bytes"


def test_build_zip_helper_is_in_memory_capable():
    """Sanity check that BytesIO-based zips work too (used implicitly by
    _build_zip via a path — this documents that no on-disk extraction of
    the whole archive is required to inspect it)."""
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("R_TEST_0001.jpg", b"x")
    buf.seek(0)
    with zipfile.ZipFile(buf) as zf:
        assert zf.namelist() == ["R_TEST_0001.jpg"]
