"""Tests for search result parsing."""

from scraper_cz.search import extract_total, extract_form_params


def test_extract_total(search_html):
    assert extract_total(search_html) == 3


def test_extract_total_no_match():
    assert extract_total("<html>no results here</html>") == 0


def test_extract_form_params(search_html):
    sp, fp = extract_form_params(search_html)
    assert sp == "sp_token_abc"
    assert fp == "fp_token_xyz"


def test_extract_form_params_missing():
    sp, fp = extract_form_params("<html>no form</html>")
    assert sp is None
    assert fp is None
