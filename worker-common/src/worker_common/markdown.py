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
_HYPHEN_END = re.compile(r"\w[-‐‑]$")

# Below this width a run of lines cannot be wrapped prose — it is a form or a
# table, where the line break carries meaning and must survive.
_MIN_WRAP_WIDTH = 40
# A wrapped line runs close to the full measure; the last line of a paragraph
# is short, which is what ends the join.
_WRAP_RATIO = 0.75
# ...and most lines in the run must do so. A form can contain one long line
# without being prose, so the widest line alone is not evidence of wrapping.
_WRAP_SHARE = 0.5


@dataclass
class Block:
    """One markdown block. `prefix` is the marker; `text` is the translatable content."""

    kind: str
    prefix: str
    text: str
    suffix: str = ""


def _strip_inline(text: str) -> str:
    return _EMPHASIS.sub("", text)


def _unwrap(lines: list[str]) -> list[str]:
    """Turn a run of consecutive lines into logical lines.

    Hard-wrapped prose is rejoined so MarianMT sees whole sentences. A form or a
    table is left alone: a wage card records one field per line, and joining those
    tears every label away from its value.

    The two are told apart by line width. Wrapped prose runs close to a constant
    measure and its final line is short, which is what terminates a join; a form is
    short and ragged and never reaches the wrap width at all.
    """
    stripped = [line.strip() for line in lines if line.strip()]
    if not stripped:
        return []

    widths = [len(line) for line in stripped]
    max_width = max(widths)
    threshold = max_width * _WRAP_RATIO
    near_full = sum(1 for w in widths if w >= threshold)
    looks_wrapped = max_width >= _MIN_WRAP_WIDTH and near_full / len(widths) >= _WRAP_SHARE

    out: list[str] = []
    current = ""
    for line in stripped:
        if not current:
            current = line
            continue
        if _HYPHEN_END.search(current) and line[:1].islower():
            # A word split across the wrap: heal it. The next line continuing in
            # lower case is what distinguishes "tschechi-/schen" from a form's
            # trailing dash, as in "Der.Antonow Kiew-" followed by "27 J.".
            current = current[:-1] + line
            continue
        if looks_wrapped and len(current) >= threshold and not current.endswith(":"):
            current = f"{current} {line}"
            continue
        out.append(current)
        current = line
    if current:
        out.append(current)
    return out


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
        if not para:
            return
        logical = _unwrap(para)
        # One block per logical line. A wrapped paragraph collapses to a single
        # block; a form yields one block per field so the layout survives.
        kind = "paragraph" if len(logical) == 1 else "line"
        for text in logical:
            blocks.append(Block(kind, "", text))
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
            or (b.kind == "line" and prev == "line")
            or (b.kind.startswith("table_") and prev.startswith("table_"))
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
