"""Tests for parsing the Invenio viewer page and building URLs/titles."""

import pytest

from scraper_barch.invenio import (
    INVENIO_BASE,
    build_image_url,
    build_title,
    first_item_page_count,
    get_pages,
    parse_viewer_page,
    viewer_url,
    volume_label,
)

from .conftest import PATH, SIGNATURE, UUID


class TestParseViewerPage:
    def test_parses_data_blob(self, sample_viewer_html, sample_data):
        data = parse_viewer_page(sample_viewer_html)
        assert data == sample_data

    def test_extracts_title_uuid_path(self, sample_viewer_html):
        data = parse_viewer_page(sample_viewer_html)
        assert data["title"] == SIGNATURE
        assert data["uuid"] == UUID
        assert data["path"] == PATH

    def test_raises_when_blob_missing(self):
        with pytest.raises(ValueError):
            parse_viewer_page("<html><body>no data here</body></html>")


class TestGetPages:
    def test_flattens_items_files(self, sample_data):
        pages = get_pages(sample_data)
        assert len(pages) == 3
        assert pages[0]["filename"] == "R_43_II_1326_0001.jpg"
        assert pages[1]["filename"] == "R_43_II_1326_0002.jpg"
        assert pages[2]["filename"] == "R_43_II_1326_0003.jpg"

    def test_empty_items(self):
        assert get_pages({"items": []}) == []

    def test_missing_items_key(self):
        assert get_pages({}) == []


class TestBuildImageUrl:
    def test_builds_url_from_path_and_filename(self, sample_data):
        url = build_image_url(sample_data, "R_43_II_1326_0001.jpg")
        assert url == f"{INVENIO_BASE}/files/{PATH}/R_43_II_1326_0001.jpg"


class TestViewerUrl:
    def test_builds_viewer_url(self):
        assert viewer_url(UUID) == f"{INVENIO_BASE}/view/{UUID}"


class TestVolumeLabel:
    def test_returns_first_ve_list_titel(self, sample_data):
        assert volume_label(sample_data) == "Bd. 8"

    def test_returns_none_when_ve_list_empty(self, sample_data_no_volume):
        assert volume_label(sample_data_no_volume) is None

    def test_returns_none_when_frontend_missing(self):
        assert volume_label({}) is None


class TestBuildTitle:
    def test_includes_volume_label(self, sample_data):
        assert build_title(sample_data) == "R 43-II/1326 (Bd. 8)"

    def test_no_volume_label_when_ve_list_empty(self, sample_data_no_volume):
        assert build_title(sample_data_no_volume) == "R 43-II/1326"


class TestFirstItemPageCount:
    def test_counts_first_item_files(self, sample_data):
        assert first_item_page_count(sample_data) == 3

    def test_raises_when_no_items(self):
        with pytest.raises(ValueError):
            first_item_page_count({"items": []})
