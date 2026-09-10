"""Tests for record metadata extraction."""

from scraper_cz.record import (
    parse_page,
    parse_record_detail,
    extract_scan_uuids,
    extract_paginator_pages,
    extract_scan_count,
    extract_entity_ref,
)


class TestParsePage:
    """Test parsing search result pages using the <aricle> split."""

    def test_extracts_all_records(self, search_html):
        results = parse_page(search_html)
        assert len(results) == 3

    def test_first_record_fields(self, search_html):
        results = parse_page(search_html)
        r = results[0]
        assert r["xid"] == "aaaa1111-bbbb-cccc-dddd-eeee00001111"
        assert r["inv"] == 100
        assert r["sig"] == "109-5/1"
        assert r["nad"] == 1799
        assert r["years"] == "1939-1942"
        assert r["scans"] == 15
        assert "Heydrich-Bormann" in r["desc"]

    def test_second_record_fields(self, search_html):
        results = parse_page(search_html)
        r = results[1]
        assert r["xid"] == "aaaa2222-bbbb-cccc-dddd-eeee00002222"
        assert r["inv"] == 200
        assert r["sig"] == "109-4/2"
        assert r["scans"] == 8

    def test_record_without_scans(self, search_html):
        results = parse_page(search_html)
        r = results[2]
        assert r["xid"] == "aaaa3333-bbbb-cccc-dddd-eeee00003333"
        assert r["scans"] == 0
        assert r["nad"] == 1800

    def test_fond_extracted(self, search_html):
        results = parse_page(search_html)
        # "Fond Alpha" comes before the bullet
        assert results[0]["fond"] == "Fond Alpha"

    def test_empty_html_returns_empty(self):
        assert parse_page("<html>no articles</html>") == []


class TestParseRecordDetail:
    def test_basic_fields(self, record_html):
        detail = parse_record_detail(record_html, "test-xid")
        assert detail["xid"] == "test-xid"
        assert detail["inv"] == 1777
        assert detail["sig"] == "109-5/5"
        assert detail["datace"] == "1942"
        assert detail["scans"] == 5

    def test_entity_ref_extracted(self, record_html):
        detail = parse_record_detail(record_html, "test-xid")
        assert detail["entity_ref"] is not None
        assert "inventare/dao/1234" in detail["entity_ref"]

    def test_fond_name(self, record_html):
        detail = parse_record_detail(record_html, "test-xid")
        assert "protektora" in detail["fond_name"].lower() or detail["fond_name"] != ""

    def test_nad_number(self, record_html):
        detail = parse_record_detail(record_html, "test-xid")
        assert detail["nad_number"] == 1799


class TestScanUuids:
    def test_extract_uuids(self, zoomify_html):
        uuids = extract_scan_uuids(zoomify_html)
        assert len(uuids) == 3
        assert uuids[0] == ("42", "aabbccdd-1122-3344-5566-778899001122")
        assert uuids[1] == ("42", "aabbccdd-1122-3344-5566-778899002233")
        assert uuids[2] == ("43", "ccddaabb-1122-3344-5566-778899003344")

    def test_deduplicates(self):
        html = """
        images/1/aabbccdd-1122-3344-5566-778899001122.jpg/thumb.jpg
        images/1/aabbccdd-1122-3344-5566-778899001122.jpg/medium.jpg
        """
        uuids = extract_scan_uuids(html)
        assert len(uuids) == 1

    def test_empty_html(self):
        assert extract_scan_uuids("<html>nothing</html>") == []


class TestPaginatorPages:
    def test_extract_pages(self, zoomify_html):
        pages = extract_paginator_pages(zoomify_html)
        assert 10 in pages
        assert pages[10] == "sp_media_token"

    def test_empty_html(self):
        assert extract_paginator_pages("<html></html>") == {}


class TestHelpers:
    def test_extract_scan_count(self):
        html = "<div>Zobrazit 12 skenů v prohlížeči</div>"
        assert extract_scan_count(html) == 12

    def test_extract_scan_count_missing(self):
        assert extract_scan_count("<html></html>") == 0

    def test_extract_entity_ref(self):
        html = '<a href="Zoomify.action?entityRef=cz%2Ftest%2F123&scan=0">view</a>'
        ref = extract_entity_ref(html)
        assert ref == "cz/test/123"

    def test_extract_entity_ref_missing(self):
        assert extract_entity_ref("<html></html>") is None


def test_title_is_the_records_subject_not_the_fond_name(record_html):
    """The fond name, inventory number and signature all travel as their own fields.

    Composing a title out of them repeated the archive's name across 2,552 records and said
    nothing the catalogue block does not already show — and once translated it put "STATE
    SECRETARY FOR THE RUSSIAN PROTECTOR IN THINGS AND IN MORAVA" at the head of 86% of records.
    """
    detail = parse_record_detail(record_html, "test-xid")
    assert detail["title"] == "Situační zpráva pro Bormanna"
    assert "říšského" not in detail["title"]
    # The catalogue fields are still carried, just not welded into the title.
    assert detail["sig"] == "109-5/5"
    assert detail["inv"] == 1777
    assert detail["fond_name"] == "Úřad říšského protektora"


def test_a_record_with_no_subject_falls_back_to_the_catalogue_label():
    """Better an identifiable label than a blank row in a list."""
    html = """
    <html><body>
    <div class="detailRecord">
      <span class="tabularLabel">Inv./přír. číslo:</span>
      <span class="tabularValue">1307</span>
      <span class="tabularLabel">Signatura:</span>
      <span class="tabularValue">110-12/133</span>
      <span class="tabularLabel">Název fondu:</span>
      <span class="tabularValue">NĚMECKÉ STÁTNÍ MINISTERSTVO</span>
    </div>
    </body></html>
    """
    detail = parse_record_detail(html, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    assert detail["title"].startswith("NĚMECKÉ STÁTNÍ MINISTERSTVO")
    assert "inv. 1307" in detail["title"]
    assert "sig. 110-12/133" in detail["title"]


def test_description_is_dropped_when_it_only_repeats_the_title():
    """One subject line, stored once — not stored, embedded and printed twice."""
    from scraper_cz.client import _description_unless_same_as_title

    subject = "Slavnostní otevření Německé šermířské školy v Praze."
    assert _description_unless_same_as_title({"title": subject, "obsah": subject}) == ""
    assert (
        _description_unless_same_as_title({"title": subject, "desc": "Something else"})
        == "Something else"
    )
