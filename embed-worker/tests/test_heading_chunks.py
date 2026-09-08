from embed_worker.chunker import chunk_document


def test_plain_text_yields_chunks_with_no_heading():
    assert chunk_document("some body text", "text/plain") == [
        {"content": "some body text", "heading": ""}
    ]


def test_markdown_sections_carry_their_heading_path():
    md = "# Bericht\n\nintro\n\n## Enteignung\n\nGegen 10 der wichtigsten"
    out = chunk_document(md, "text/markdown")
    assert out[0]["heading"] == "Bericht"
    assert out[1]["heading"] == "Bericht > Enteignung"


def test_heading_is_prefixed_to_the_embedded_content():
    # A chunk from the middle of a section is otherwise topic-less: BGE-M3 and
    # Qwen3 alike embed only what they are given.
    md = "## Enteignung\n\nGegen 10 der wichtigsten"
    out = chunk_document(md, "text/markdown")
    assert out[0]["content"].startswith("Enteignung\n\n")
    assert "Gegen 10" in out[0]["content"]


def test_long_sections_are_split_but_each_piece_keeps_the_heading():
    md = "## Enteignung\n\n" + ("wort " * 900)
    out = chunk_document(md, "text/markdown", max_chars=500)
    assert len(out) > 1
    assert all(c["heading"] == "Enteignung" for c in out)
    assert all(c["content"].startswith("Enteignung\n\n") for c in out)
