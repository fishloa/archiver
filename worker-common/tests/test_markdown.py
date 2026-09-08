"""Markdown handling shared by the PDF, translate and embed workers."""

import pathlib

from worker_common.markdown import (
    MARKDOWN,
    PLAIN,
    iter_sections,
    parse_blocks,
    render_blocks,
    to_plain_text,
)


def test_plain_text_is_returned_untouched():
    # A typescript page number is byte-identical to a markdown bullet, so a page
    # declared text/plain must never be reinterpreted.
    assert to_plain_text("- 5 -\n\nsome body", PLAIN) == "- 5 -\n\nsome body"


def test_markdown_headings_lose_their_markers():
    assert to_plain_text("# Der Reichsprotektor\n\nin Bohmen", MARKDOWN) == (
        "Der Reichsprotektor\n\nin Bohmen"
    )


def test_markdown_emphasis_and_bullets_are_stripped():
    assert to_plain_text("- **Kinsky**, Czernin", MARKDOWN) == "Kinsky, Czernin"


def test_markdown_table_becomes_readable_text():
    md = "| Name | Jahr |\n| --- | --- |\n| Czernin | 1942 |"
    assert to_plain_text(md, MARKDOWN) == "Name  Jahr\nCzernin  1942"


def test_parse_blocks_keeps_the_heading_marker_out_of_the_text():
    blocks = parse_blocks("# Enteignung\n\nGegen 10 der wichtigsten", MARKDOWN)
    assert [(b.kind, b.prefix, b.text) for b in blocks] == [
        ("heading", "# ", "Enteignung"),
        ("paragraph", "", "Gegen 10 der wichtigsten"),
    ]


def test_parse_blocks_unwraps_hard_wrapped_prose():
    # The typescript wraps mid-sentence and hyphenates across lines. MarianMT wants
    # whole sentences, so a paragraph must be rejoined before translation.
    md = "Gegen 10 der wichtigsten tschechi-\nschen Adligen habe ich"
    blocks = parse_blocks(md, MARKDOWN)
    assert blocks[0].text == "Gegen 10 der wichtigsten tschechischen Adligen habe ich"


def test_parse_blocks_treats_plain_text_as_paragraphs_only():
    blocks = parse_blocks("- 5 -\n\nbody text", PLAIN)
    assert [b.kind for b in blocks] == ["paragraph", "paragraph"]
    assert blocks[0].text == "- 5 -"


def test_render_blocks_round_trips_a_heading():
    md = "# Enteignung\n\nGegen 10 der wichtigsten"
    assert render_blocks(parse_blocks(md, MARKDOWN)) == md


def test_render_blocks_reattaches_translated_text_to_its_marker():
    blocks = parse_blocks("# Enteignung", MARKDOWN)
    blocks[0].text = "Expropriation"
    assert render_blocks(blocks) == "# Expropriation"


def test_iter_sections_pairs_each_heading_with_its_body():
    md = "# Bericht\n\nintro text\n\n## Enteignung\n\nGegen 10 der wichtigsten"
    assert iter_sections(md, MARKDOWN) == [
        ("Bericht", "intro text"),
        ("Bericht > Enteignung", "Gegen 10 der wichtigsten"),
    ]


def test_iter_sections_on_plain_text_yields_one_untitled_section():
    assert iter_sections("just some text", PLAIN) == [("", "just some text")]


# ---------------------------------------------------------------------------
# Regression fixtures taken verbatim from the archive.
#
# Translation used to flatten every page to a single line — 120,258 of the
# 120,386 translated pages have zero newlines in their English text. The first
# fix unwrapped hard-wrapped prose, which is right for a report but destroyed
# forms: a wage card records one field per line, and joining those tears every
# label away from its value. These fixtures hold both shapes so neither
# regresses into the other.
# ---------------------------------------------------------------------------

FIXTURES = pathlib.Path(__file__).parent / "fixtures"


def _fixture(name: str) -> str:
    return (FIXTURES / name).read_text()


def _nonblank(text: str) -> int:
    return len([line for line in text.split("\n") if line.strip()])


def _round_trip(text: str, content_type: str) -> str:
    return render_blocks(parse_blocks(text, content_type))


def test_wage_card_keeps_each_field_on_its_own_line():
    # Record 3468 "TSCHERNIN DIMITRO", Arolsen — a Lohnkarte, one field per line.
    # Before the fix the whole card became a single line and every label was torn
    # from its value: "Entry: Exit: Wage card for 1942 No. ... Marital status:".
    src = _fixture("form_lohnkarte_plain.txt")
    out = _round_trip(src, PLAIN)

    for field in ("Eintritt: 13.9.40", "Wohnort: Antonow", "Steuerkarte Nr. 1066"):
        assert field in out.split("\n"), f"{field!r} is no longer on its own line"

    # Some column headers are single words stacked vertically in narrow columns
    # ("Lohn-/aus-/zahlung"), and rejoining those is correct — so the line count
    # drops a little. What must not happen is wholesale collapse.
    assert _nonblank(out) > _nonblank(src) * 0.75


def test_wage_card_does_not_treat_a_trailing_dash_as_a_broken_word():
    # "Der.Antonow Kiew-" is a dash, not a word split across a wrap.
    out = _round_trip(_fixture("form_lohnkarte_plain.txt"), PLAIN)
    assert "Der.Antonow Kiew-" in out
    assert "Kiew27" not in out


def test_prisoner_form_survives_one_unusually_long_line():
    # Record 3410 "CZERNIN HUMPRECHT" — a form whose longest line is 72 characters
    # while the median is 15. One long line is not evidence of wrapped prose.
    src = _fixture("form_czernin_humprecht_plain.txt")
    out = _round_trip(src, PLAIN)
    assert _nonblank(out) > _nonblank(src) * 0.75
    assert "Gefangenenbuch" in out.split("\n")


def test_hard_wrapped_prose_is_joined_into_paragraphs():
    # Record 2549 seq 81 — a witness statement wrapped at ~57 characters.
    src = _fixture("prose_wrapped_plain.txt")
    out = _round_trip(src, PLAIN)
    assert _nonblank(out) < _nonblank(src) / 5


def test_hard_wrapped_prose_heals_words_split_across_the_wrap():
    # "Schleimhautblu-\ntungen" must become one word for MarianMT.
    out = _round_trip(_fixture("prose_wrapped_plain.txt"), PLAIN)
    assert "Schleimhautblutungen" in out
    assert "Schleimhautblu-" not in out
