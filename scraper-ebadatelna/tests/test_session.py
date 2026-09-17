"""Tests for the ebadatelna.cz session.

Every endpoint shape asserted here was verified against the live site; these
tests exist to stop a "tidy-up" from silently changing a parameter name the
server answers with HTTP 500 and an HTML error page.
"""

import json

import pytest
from pytest_httpx import HTTPXMock

from scraper_ebadatelna.session import (
    EBadatelnaSession,
    normalise_signature_id,
)

from .conftest import FOLDER_RESULT_HTML, HOMEPAGE_HTML, NODE_INFO_HTML

BASE = "https://ebadatelna.cz"


@pytest.fixture
def session():
    s = EBadatelnaSession()
    yield s
    s.close()


class TestNormaliseSignatureId:
    def test_prefixes_bare_number_from_ocr_search(self):
        assert normalise_signature_id("626290") == "f626290"

    def test_leaves_tree_id_alone(self):
        assert normalise_signature_id("f626290") == "f626290"
        assert normalise_signature_id("s1354") == "s1354"


class TestLogin:
    def _homepage(self, httpx_mock):
        httpx_mock.add_response(url=BASE + "/", text=HOMEPAGE_HTML)

    def test_sends_antiforgery_token_as_header(self, session, httpx_mock: HTTPXMock):
        self._homepage(httpx_mock)
        httpx_mock.add_response(
            url=BASE + "/Account/Login",
            text="1",
            headers={
                "set-cookie": "RESEARCHER_INFO=EMAIL=researcher@example.com&ID=1; path=/"
            },
        )

        assert session.login() is True

        login_request = httpx_mock.get_requests()[-1]
        assert login_request.headers["__RequestVerificationToken"] == "tok-abc-123"
        assert login_request.headers["X-Requested-With"] == "XMLHttpRequest"
        body = login_request.read().decode()
        assert "email=researcher%40example.com" in body
        assert "password=s3cret" in body

    def test_response_1_means_verified(self, session, httpx_mock: HTTPXMock):
        self._homepage(httpx_mock)
        httpx_mock.add_response(
            url=BASE + "/Account/Login",
            text="1",
            headers={"set-cookie": "RESEARCHER_INFO=EMAIL=x&ID=1; path=/"},
        )
        session.login()
        assert session.authenticated is True
        assert session.verified is True
        assert session.researcher_cookie.startswith("EMAIL=")

    def test_other_response_is_authenticated_but_unverified(
        self, session, httpx_mock: HTTPXMock
    ):
        # An unverified account logs in happily and then finds nothing at all.
        self._homepage(httpx_mock)
        httpx_mock.add_response(
            url=BASE + "/Account/Login",
            text="0",
            headers={"set-cookie": "RESEARCHER_INFO=EMAIL=x&ID=1; path=/"},
        )
        session.login()
        assert session.authenticated is True
        assert session.verified is False

    def test_no_cookie_means_failure(self, session, httpx_mock: HTTPXMock):
        self._homepage(httpx_mock)
        httpx_mock.add_response(url=BASE + "/Account/Login", text="0")
        assert session.login() is False
        assert session.authenticated is False

    def test_missing_credentials_skips_login(self, session, httpx_mock: HTTPXMock):
        from scraper_ebadatelna.config import get_config

        get_config().ebadatelna_email = ""
        assert session.login() is False
        assert httpx_mock.get_requests() == []


class TestItemRead:
    def test_node_id_goes_in_the_query_string(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/Item_Read?id=s1354",
            json={"Data": [{"Id": "s1472", "Signature": "325"}], "Total": 1},
        )
        items, total = session.item_read("s1354")
        assert total == 1
        assert items[0]["Signature"] == "325"
        assert httpx_mock.get_requests()[0].read().decode() == "sort=&group=&filter="

    def test_root_listing_has_no_id(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/Item_Read", json={"Data": [], "Total": 0}
        )
        session.item_read()
        assert "id=" not in str(httpx_mock.get_requests()[0].url)


class TestOcrSearch:
    def test_sends_query_and_mandatory_date_range(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/OcrFulltextRead",
            json={"Data": [{"Id": "626290", "TotalNum": 156}], "Total": 82},
        )
        rows, total = session.ocr_search("Czernin", page_size=50)
        assert total == 82
        assert rows[0]["TotalNum"] == 156

        body = httpx_mock.get_requests()[0].read().decode()
        assert "query=Czernin" in body
        assert "pageSize=50" in body
        assert "dateFrom=1885" in body
        assert "dateTo=1993" in body

    def test_signature_and_node_filters_are_indexed(
        self, session, httpx_mock: HTTPXMock
    ):
        httpx_mock.add_response(
            url=BASE + "/Home/OcrFulltextRead", json={"Data": [], "Total": 0}
        )
        session.ocr_search(
            "Czernin", signatures=["325-113-1"], node_filter_ids=["s1472"]
        )
        body = httpx_mock.get_requests()[0].read().decode()
        assert "signatures%5B0%5D=325-113-1" in body
        assert "nodeFilterIds%5B0%5D=s1472" in body


class TestGetSignatureImages:
    def test_posts_json_body_with_f_prefixed_id(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetSignatureImages",
            json={
                "ThumbnailList": ["tok1", "tok2"],
                "TotalImages": 2,
                "TotalPages": 1,
                "SignatureName": "325-113-1",
                "ImageTypes": [{"Name": "Běžné skeny", "Value": 1, "Count": 2}],
            },
        )
        # OCR search hands over the bare number; the endpoint needs the prefix.
        session.get_signature_images("626290")
        body = json.loads(httpx_mock.get_requests()[0].read())
        assert body == {
            "signatureId": "f626290",
            "page": 1,
            "imageType": 1,
            "sh": True,
        }

    def test_all_page_tokens_walks_every_page(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetSignatureImages",
            json={
                "ThumbnailList": [f"tok{i}" for i in range(200)],
                "TotalImages": 214,
                "TotalPages": 2,
                "ImageTypes": [{"Value": 1, "Count": 214}],
            },
            is_reusable=False,
        )
        httpx_mock.add_response(
            url=BASE + "/Home/GetSignatureImages",
            json={
                "ThumbnailList": [f"tok{i}" for i in range(200, 214)],
                "TotalImages": 214,
                "TotalPages": 2,
                "ImageTypes": [{"Value": 1, "Count": 214}],
            },
        )
        meta, tokens = session.all_page_tokens("f626290")
        assert len(tokens) == 214
        assert "ThumbnailList" not in meta
        assert meta["TotalImages"] == 214
        assert json.loads(httpx_mock.get_requests()[1].read())["page"] == 2


class TestNodeInfo:
    def test_splits_description_from_signature(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetNodeInfo?id=f626290", text=NODE_INFO_HTML
        )
        info = session.node_info("626290")
        assert info["signature"] == "325-113-1"
        assert "Humprechta Czernina z Chudenic" in info["description"]
        assert "<b>" not in info["description"]
        assert "signatura" not in info["description"]


class TestParentPath:
    def test_fond_path_joins_ancestor_names(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetParentPath?id=f626290",
            json=[
                {"Id": "s1354", "Name": "Fondy tzv. Studijního ústavu MV"},
                {"Id": "s1472", "Name": "Stíhání nacistických"},
                {"Id": "f626290", "Name": "Svazek"},
            ],
        )
        assert session.fond_path("626290") == (
            "Fondy tzv. Studijního ústavu MV > Stíhání nacistických > Svazek"
        )

    def test_non_json_answer_is_not_fatal(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetParentPath?id=f1", text="<html>500</html>"
        )
        assert session.parent_path("f1") == []


class TestFolderHits:
    def test_parses_scan_numbers_and_snippets(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/OcrFolderResult?folderId=626290&query=Humprecht",
            text=FOLDER_RESULT_HTML,
        )
        hits = session.folder_hits("f626290", "Humprecht")
        assert [h["scan_number"] for h in hits] == [29, 44]
        # The click handler's index is 0-based; the printed number is 1-based.
        assert [h["scan_index"] for h in hits] == [28, 43]
        assert hits[0]["signature_id"] == "f626290"
        assert hits[0]["snippet_url"].startswith(
            BASE + "/Scan/GetImageSnippet?path=TOKEN1&w="
        )
        assert "&amp;" not in hits[0]["snippet_url"]


class TestImages:
    def test_get_image_passes_token_as_page(self, session, httpx_mock: HTTPXMock):
        httpx_mock.add_response(
            url=BASE + "/Home/GetImage?page=tok1", content=b"\xff\xd8jpeg"
        )
        assert session.get_image("tok1") == b"\xff\xd8jpeg"

    def test_record_url_is_a_deep_link(self):
        assert (
            EBadatelnaSession.record_url("626290")
            == "https://ebadatelna.cz/?id=f626290"
        )
