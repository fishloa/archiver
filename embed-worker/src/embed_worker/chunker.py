"""Text chunking for embedding."""

from worker_common.markdown import iter_sections


def chunk_text(text: str, max_chars: int = 2000, overlap: int = 200) -> list[str]:
    """Split text into overlapping chunks.

    Uses ~2000 chars per chunk (~500 tokens for English text).
    Tries to break at paragraph or sentence boundaries.
    """
    if not text or not text.strip():
        return []

    text = text.strip()

    if len(text) <= max_chars:
        return [text]

    chunks = []
    start = 0
    while start < len(text):
        end = start + max_chars

        if end < len(text):
            # Try to break at paragraph boundary
            para_break = text.rfind("\n\n", start + max_chars // 2, end)
            if para_break > start:
                end = para_break
            else:
                # Try sentence boundary
                for sep in (". ", ".\n", "! ", "? "):
                    sent_break = text.rfind(sep, start + max_chars // 2, end)
                    if sent_break > start:
                        end = sent_break + 1
                        break
                else:
                    # Try word boundary
                    space = text.rfind(" ", start + max_chars // 2, end)
                    if space > start:
                        end = space

        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)

        # Move start forward, accounting for overlap
        start = end - overlap if end < len(text) else end

    return chunks


def chunk_document(
    text: str, content_type: str, max_chars: int = 2000, overlap: int = 200
) -> list[dict]:
    """Chunk a page, keeping each chunk tied to its section heading.

    The heading path is prefixed to the embedded content so a chunk taken from the
    middle of a section still carries that section's subject. Plain-text pages have
    no headings and fall back to the previous behaviour.
    """
    out: list[dict] = []
    for heading, body in iter_sections(text, content_type):
        for piece in chunk_text(body, max_chars=max_chars, overlap=overlap):
            content = f"{heading}\n\n{piece}" if heading else piece
            out.append({"content": content, "heading": heading})
    return out
