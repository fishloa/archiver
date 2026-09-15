"""Search-filter regression tests for the DDB Solr query."""

from unittest.mock import MagicMock

from scraper_ddb.session import DDBSession


def captured_params(monkeypatch=None):
    s = DDBSession.__new__(DDBSession)
    client = MagicMock()
    client.get.return_value.json.return_value = {"response": {"docs": [], "numFound": 0}}
    s._client = client
    s.search("Czernin")
    return client.get.call_args.kwargs["params"]


def test_archival_files_are_not_filtered_out():
    # mediatype_007 is an Akte. Filtering to mediatype_003 alone cut "Czernin"
    # in the archive sector from 110 hits to 5, losing Humprecht's prisoner
    # file and the Nuremberg interrogations of Felix.
    fq = captured_params()["fq"]
    assert any("mediatype_007" in f for f in fq)
    assert any("mediatype_003" in f for f in fq)
    assert "type_fct:mediatype_003" not in fq


def test_search_stays_in_the_archive_sector():
    assert "sector_fct:sec_01" in captured_params()["fq"]
