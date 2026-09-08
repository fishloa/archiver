"""The invisible text layer must never contain markdown markers.

A reader selecting text out of the searchable PDF would otherwise copy '#' and '**'
along with the transcription.
"""

from worker_common.markdown import to_plain_text


def _layer_text(page_meta: dict) -> str:
    """Mirror of the extraction in pdf_worker.main."""
    return to_plain_text(
        page_meta.get("text_raw", "") or "",
        page_meta.get("content_type") or "text/plain",
    )


def test_markdown_markers_are_absent_from_the_layer():
    meta = {"text_raw": "# Der Reichsprotektor\n\n- **Czernin**", "content_type": "text/markdown"}
    out = _layer_text(meta)
    assert "#" not in out
    assert "*" not in out
    assert "Der Reichsprotektor" in out
    assert "Czernin" in out


def test_plain_pages_keep_their_leading_dashes():
    meta = {"text_raw": "- 5 -\n\nbody", "content_type": "text/plain"}
    assert _layer_text(meta) == "- 5 -\n\nbody"


def test_missing_content_type_is_treated_as_plain():
    meta = {"text_raw": "- 5 -"}
    assert _layer_text(meta) == "- 5 -"
