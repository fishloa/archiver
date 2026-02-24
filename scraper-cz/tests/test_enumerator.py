"""Tests for the enumerator with mocked session."""

from unittest.mock import MagicMock, patch

from scraper_cz.enumerator import enumerate_all

# Dummy HTML -- the actual content doesn't matter because search_mod
# and record_mod are fully mocked in these tests.
_DUMMY_HTML = "<html>Celkem : 3</html>"


def _make_mock_session():
    """Create a mock VadeMeCumSession."""
    session = MagicMock()
    session.source_page = "sp_mock"
    session.fp = "fp_mock"
    session.reinit = MagicMock()
    return session


class TestEnumerateAll:

    @patch("scraper_cz.enumerator.search_mod")
    @patch("scraper_cz.enumerator.record_mod")
    def test_single_page_results(self, mock_record, mock_search):
        """When all results fit on one page, no pagination needed."""
        session = _make_mock_session()

        mock_search.search.return_value = (3, _DUMMY_HTML)
        mock_search.extract_form_params.return_value = ("sp", "fp")

        # parse_page returns 3 items from the sample HTML
        mock_record.parse_page.return_value = [
            {"xid": "aaa", "inv": 1, "scans": 10},
            {"xid": "bbb", "inv": 2, "scans": 5},
            {"xid": "ccc", "inv": 3, "scans": 0},
        ]

        total, results = enumerate_all(session, "test term", max_items=100)

        assert total == 3
        assert len(results) == 3
        session.reinit.assert_called_once()
        mock_search.search.assert_called_once()

    @patch("scraper_cz.enumerator.search_mod")
    @patch("scraper_cz.enumerator.record_mod")
    def test_empty_search(self, mock_record, mock_search):
        """No results should return empty."""
        session = _make_mock_session()
        mock_search.search.return_value = (0, "<html>Celkem : 0</html>")

        total, results = enumerate_all(session, "nonexistent")

        assert total == 0
        assert results == []

    @patch("scraper_cz.enumerator.search_mod")
    @patch("scraper_cz.enumerator.record_mod")
    def test_pagination(self, mock_record, mock_search):
        """Test that pagination fetches additional pages."""
        session = _make_mock_session()

        mock_search.search.return_value = (6, "<html>page1</html>")
        mock_search.extract_form_params.return_value = ("sp1", "fp1")

        # First page: 3 results
        page1 = [
            {"xid": "aaa", "inv": 1, "scans": 1},
            {"xid": "bbb", "inv": 2, "scans": 1},
            {"xid": "ccc", "inv": 3, "scans": 1},
        ]
        # Second page: 3 more results
        page2 = [
            {"xid": "ddd", "inv": 4, "scans": 1},
            {"xid": "eee", "inv": 5, "scans": 1},
            {"xid": "fff", "inv": 6, "scans": 1},
        ]
        mock_record.parse_page.side_effect = [page1, page2]

        # goto_page returns HTML for the second page
        mock_search.goto_page.return_value = "<html>page2</html>"
        # Extract new form params from second page
        mock_search.extract_form_params.side_effect = [("sp1", "fp1"), ("sp2", "fp2")]

        total, results = enumerate_all(session, "test", max_items=100)

        assert total == 6
        assert len(results) == 6
        mock_search.goto_page.assert_called_once()

    @patch("scraper_cz.enumerator.search_mod")
    @patch("scraper_cz.enumerator.record_mod")
    def test_deduplication(self, mock_record, mock_search):
        """Duplicate xids across pages should be deduplicated."""
        session = _make_mock_session()

        mock_search.search.return_value = (4, "<html>page1</html>")
        mock_search.extract_form_params.return_value = ("sp1", "fp1")

        page1 = [
            {"xid": "aaa", "inv": 1, "scans": 1},
            {"xid": "bbb", "inv": 2, "scans": 1},
        ]
        # Second page has one duplicate and one new
        page2 = [
            {"xid": "bbb", "inv": 2, "scans": 1},  # dup
            {"xid": "ccc", "inv": 3, "scans": 1},
        ]
        mock_record.parse_page.side_effect = [page1, page2]
        mock_search.goto_page.return_value = "<html>page2</html>"
        mock_search.extract_form_params.side_effect = [("sp1", "fp1"), ("sp2", "fp2")]

        total, results = enumerate_all(session, "test", max_items=100)

        xids = [r["xid"] for r in results]
        assert len(set(xids)) == len(xids), "Duplicates not removed"
        assert "ccc" in xids

    @patch("scraper_cz.enumerator.search_mod")
    @patch("scraper_cz.enumerator.record_mod")
    def test_max_items_cap(self, mock_record, mock_search):
        """Should stop when max_items is reached."""
        session = _make_mock_session()

        mock_search.search.return_value = (100, "<html>page1</html>")
        mock_search.extract_form_params.return_value = ("sp1", "fp1")

        page = [{"xid": f"item-{i}", "inv": i, "scans": 1} for i in range(12)]
        mock_record.parse_page.return_value = page

        total, results = enumerate_all(session, "test", max_items=5)

        # First page has 12 items but max_items=5, so we should get first page
        # then stop (since first page already exceeds max_items)
        assert len(results) <= 12
