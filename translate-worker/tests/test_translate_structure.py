"""Translation must preserve the media type of its input.

A text/markdown page yields markdown text_en: headings stay headings.
"""

from worker_common.markdown import MARKDOWN, PLAIN, parse_blocks, render_blocks


class FakeTranslator:
    """Stands in for MarianMT: uppercases, so structure changes are visible."""

    def translate_block_text(self, text: str) -> str:
        return text.upper()


def _translate(text: str, content_type: str) -> str:
    t = FakeTranslator()
    blocks = parse_blocks(text, content_type)
    for b in blocks:
        if b.kind in ("table_divider",):
            continue
        b.text = t.translate_block_text(b.text)
    return render_blocks(blocks)


def test_heading_marker_survives_translation():
    assert _translate("# Enteignung", MARKDOWN) == "# ENTEIGNUNG"


def test_paragraph_breaks_survive_translation():
    out = _translate("# Enteignung\n\nGegen 10", MARKDOWN)
    assert out == "# ENTEIGNUNG\n\nGEGEN 10"
    assert "\n\n" in out


def test_hard_wrapped_prose_is_unwrapped_into_one_sentence():
    out = _translate("Gegen 10 der wichtigsten tschechi-\nschen Adligen", MARKDOWN)
    assert out == "GEGEN 10 DER WICHTIGSTEN TSCHECHISCHEN ADLIGEN"


def test_plain_text_gains_no_markdown():
    out = _translate("- 5 -\n\nbody", PLAIN)
    assert out == "- 5 -\n\nBODY"
