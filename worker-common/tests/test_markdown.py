"""Markdown handling shared by the PDF, translate and embed workers."""

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
