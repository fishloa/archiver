from scraper_findbuch.session import FindbuchSession


def test_first_page_has_no_page_parameter():
    assert FindbuchSession.search_url("Czernin") == (
        "/findbuch-search/searchterm/Czernin/perPage/100"
    )


def test_later_pages_use_a_query_parameter():
    # A /page/2 path segment is accepted and ignored by findbuch.at, which
    # silently returns page 1 — the whole point of this test.
    url = FindbuchSession.search_url("Czernin", page=2)
    assert url == "/findbuch-search/searchterm/Czernin/perPage/100?page=2"
    assert "/page/" not in url


def test_per_page_is_settable():
    assert FindbuchSession.search_url("Czernin", page=3, per_page=20).endswith(
        "/perPage/20?page=3"
    )
