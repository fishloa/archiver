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


def test_sort_links_are_not_records():
    from scraper_findbuch.parser import parse_search_results

    html = """
    <div class="mod_metamodel_list">
      <a href="findbuch-search/searchterm/Czernin/orderBy/surname/orderDir/ASC">Name</a>
      <a href="https://www.findbuch.at/detail-view/17830">Czernin, Arthur, 14-11-1880</a>
    </div>
    """
    rows = parse_search_results(html)
    assert [r["detail_url"] for r in rows] == [
        "https://www.findbuch.at/detail-view/17830"
    ]
