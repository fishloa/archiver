"""Markdown handling shared by every Python worker.

Mistral's OCR model returns markdown; every other engine returns flat text. The two
are not distinguishable by inspection — a typescript's centred page number "- 5 -" is
byte-identical to a markdown bullet — so callers must pass the content_type recorded
on page_text rather than guess.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

PLAIN = "text/plain"
MARKDOWN = "text/markdown"

_HEADING = re.compile(r"^(#{1,6}\s+)(.*)$")
_LIST_ITEM = re.compile(r"^(\s*(?:[-*+]|\d+\.)\s+)(.*)$")
_BLOCKQUOTE = re.compile(r"^(\s*>\s?)(.*)$")
_TABLE_DIVIDER = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
_EMPHASIS = re.compile(r"(\*\*|__|\*|_|`)")
_HYPHEN_WRAP = re.compile(r"(\w)[-‐‑]\n(\w)")


@dataclass
class Block:
    """One markdown block. `prefix` is the marker; `text` is the translatable content."""

    kind: str
    prefix: str
    text: str
    suffix: str = ""


def _strip_inline(text: str) -> str:
    return _EMPHASIS.sub("", text)


def _unwrap(lines: list[str]) -> str:
    """Rejoin hard-wrapped lines into one logical line, healing split words."""
    joined = "\n".join(lines)
    joined = _HYPHEN_WRAP.sub(r"\1\2", joined)
    return " ".join(part.strip() for part in joined.split("\n") if part.strip())


def to_plain_text(text: str, content_type: str) -> str:
    """Project text to markup-free plain text.

    Used where markup would be actively harmful: the PDF's invisible text layer, where
    literal '#' would land in the reader's copy-paste buffer.
    """
    if content_type != MARKDOWN or not text:
        return text

    out: list[str] = []
    for line in text.split("\n"):
        if _TABLE_DIVIDER.match(line) and "|" in line:
            continue
        if "|" in line and line.strip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            out.append(_strip_inline("  ".join(c for c in cells if c)))
            continue
        m = _HEADING.match(line)
        if m:
            out.append(_strip_inline(m.group(2)).strip())
            continue
        m = _LIST_ITEM.match(line)
        if m:
            out.append(_strip_inline(m.group(2)).strip())
            continue
        m = _BLOCKQUOTE.match(line)
        if m:
            out.append(_strip_inline(m.group(2)).strip())
            continue
        out.append(_strip_inline(line))
    return "\n".join(out)


def parse_blocks(text: str, content_type: str) -> list[Block]:
    """Split into blocks whose marker and translatable text are separated."""
    blocks: list[Block] = []
    if not text:
        return blocks

    para: list[str] = []

    def flush() -> None:
        if para:
            blocks.append(Block("paragraph", "", _unwrap(para)))
            para.clear()

    for line in text.split("\n"):
        if not line.strip():
            flush()
            continue

        if content_type != MARKDOWN:
            para.append(line)
            continue

        m = _HEADING.match(line)
        if m:
            flush()
            blocks.append(Block("heading", m.group(1), m.group(2).strip()))
            continue
        m = _LIST_ITEM.match(line)
        if m:
            flush()
            blocks.append(Block("list_item", m.group(1), m.group(2).strip()))
            continue
        if "|" in line and line.strip().startswith("|"):
            flush()
            kind = "table_divider" if _TABLE_DIVIDER.match(line) else "table_row"
            blocks.append(Block(kind, "", line.strip()))
            continue
        para.append(line)

    flush()
    return blocks


def render_blocks(blocks: list[Block]) -> str:
    """Reassemble blocks, reattaching each marker to (possibly translated) text."""
    parts: list[str] = []
    for i, b in enumerate(blocks):
        rendered = f"{b.prefix}{b.text}{b.suffix}"
        if i == 0:
            parts.append(rendered)
            continue
        prev = blocks[i - 1].kind
        tight = (
            (b.kind == "list_item" and prev == "list_item")
            or b.kind.startswith("table_")
            and prev.startswith("table_")
        )
        parts.append(("\n" if tight else "\n\n") + rendered)
    return "".join(parts)


def iter_sections(text: str, content_type: str) -> list[tuple[str, str]]:
    """Split into (heading_path, body) pairs.

    The heading path is prefixed to each embedding chunk so a chunk taken from the
    middle of a section still carries that section's subject.
    """
    if content_type != MARKDOWN:
        return [("", text)] if text else []

    sections: list[tuple[str, str]] = []
    stack: list[tuple[int, str]] = []
    body: list[str] = []

    def path() -> str:
        return " > ".join(t for _, t in stack)

    def flush() -> None:
        joined = "\n".join(body).strip()
        if joined:
            sections.append((path(), joined))
        body.clear()

    for line in text.split("\n"):
        m = _HEADING.match(line)
        if m:
            flush()
            level = len(m.group(1).strip())
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, _strip_inline(m.group(2)).strip()))
            continue
        body.append(line)

    flush()
    return sections
