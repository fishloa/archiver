"""Detail-page parsing, against a real findbuch.at page.

The fixture is the record body of https://www.findbuch.at/detail-view/272117.
"""

from pathlib import Path

from scraper_findbuch.main import _build_description
from scraper_findbuch.parser import parse_detail_page

FIXTURE = (Path(__file__).parent / "fixtures" / "detail_view.html").read_text()


def parsed():
    return parse_detail_page(FIXTURE, "https://www.findbuch.at/detail-view/272117")


def test_person_fields():
    d = parsed()
    assert d["surname"] == "Rakower"
    assert d["forename"].startswith("Arthur")
    assert d["dateOfBirth"] == "12-6-1899"


def test_place_fields():
    d = parsed()
    assert d["street"] == "Czerningasse 6/I/III"
    assert d["city"] == "Wien"
    assert d["district"] == "2"
    assert d["address"] == "Czerningasse 6/I/III, Wien (2. district)"


def test_archival_reference_is_captured():
    # The signature is the orderable reference; without it the record cannot
    # be turned into a request to the archive.
    d = parsed()
    assert d["file_number"] == "6822"
    assert "AT" in d["signature"] and "FLD" in d["signature"]
    assert d["holding"].startswith("Archiv der Republik")


def test_label_and_value_do_not_run_together():
    d = parsed()
    assert d["file_type"].startswith("Restitution files")
    assert "File TypeRestitution" not in " ".join(
        v for v in d.values() if isinstance(v, str)
    )


def test_description_carries_the_record():
    text = _build_description(parsed())
    for expected in (
        "Surname: Rakower",
        "Date of Birth: 12-6-1899",
        "Street: Czerningasse 6/I/III",
        "File Number: 6822",
    ):
        assert expected in text
    # Site boilerplate stays out of the embedded text.
    assert "Provenance" not in text
    assert "Information on Data Processing" not in text


def test_a_field_with_no_label_contributes_its_value_alone():
    # Some records carry an unlabelled field (cadastral details, court
    # outcomes). It rendered as ": KG: Linz;EZ: 420".
    html = """
    <div class="field remarks">
      <div class="label grid3"></div>
      <div class="value grid9">KG: Linz;EZ: 420</div>
    </div>
    """
    text = _build_description(parse_detail_page(html))
    assert text == "KG: Linz;EZ: 420"
    assert not text.startswith(":")
