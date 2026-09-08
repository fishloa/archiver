# Markdown-Aware OCR Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the media type of every OCR transcription through the pipeline so markdown structure survives translation and is exploited by embedding, without breaking the plain-text engines.

**Architecture:** Each OCR worker declares the media type of what it wrote (`page_text.content_type`). Downstream workers never sniff the format — they read the declared type and dispatch. A single shared module in `worker-common` owns all markdown handling: converting to a plain projection (for the PDF text layer), splitting into translatable blocks (so translation is markdown-in / markdown-out), and splitting into heading-scoped sections (so embedding chunks carry their section title). `text_raw` is never rewritten on ingest — it stays verbatim as the engine produced it, which matters because this archive backs a citizenship application.

**Tech Stack:** Java 25 / Spring Boot 4 (backend, Flyway, Spring Data JDBC), Python 3.11 workers sharing `worker-common`, PostgreSQL 18 + pgvector, MarianMT (translate), BGE-M3 via TEI (embed).

**Spec:** This document. The design was settled in conversation on 2026-09-08; the decisive evidence is recorded in "Background" below.

## Background — why content type must be stored, not inferred

Mistral's OCR model returns markdown; paddle, Qwen, Claude and PDFBox return flat text. These are **not distinguishable by inspection**. The centred page number on a typescript folio is emitted by both Opus (plain) and Mistral (markdown) as the byte-identical line:

```
- 5 -
```

In markdown that renders as a bullet list containing "5 -". Any attempt to sniff the format, or to canonicalise everything to markdown, silently turns page numbers into bullets across ~128,000 pages. Hence: store the declared type, never guess.

Benchmark that motivated adopting a markdown engine (38 pages of record 3796, scored against Claude Opus 5):

| engine | similarity | word loss |
|---|---|---|
| `mistral-ocr-latest` | 0.975 | 2.4% |
| Mistral Small 4 vision (local) | 0.966 | 4.6% |
| Llama-4-Scout vision | 0.961 | 5.6% |
| Mistral-Small-3.2 vision | 0.958 | 5.4% |

## Global Constraints

- `page_text.text_raw` is **never** modified on write. No escaping, no normalising. Consumers adapt to it.
- Media types are IANA strings: `text/plain`, `text/markdown`. Not an enum — new engines must not require a migration.
- **Translation preserves the media type of its input.** A `text/markdown` page yields markdown `text_en`. This is an invariant, tested, not a second column.
- Existing rows are `text/plain` unless `engine = 'mistral-ocr'`. There are ~66,796 paddle and ~69,000 qwen rows; none are markdown.
- Python: `ruff` clean, line-length 100, target py310. Java: `./gradlew spotlessApply` before every commit.
- Backend tests need Docker (Testcontainers). If Docker is unavailable locally, say so and rely on CI — do not claim tests passed.
- Latest applied migration is **V23** (`page_text.content_type`). New migrations start at **V24**.
- **Embedding model is `Qwen/Qwen3-Embedding-8B` at `dimensions: 1024`.** Passages get no
  prefix; queries get Qwen3's instruction prefix. The worker and `SemanticSearchController`
  must stay in step — a mismatch degrades retrieval silently, with no error raised.
- **Re-embedding is never run by the implementer.** Task 5 lands the code; the user runs
  the pass and the subsequent `CREATE INDEX`.
- Never commit an API key. `MISTRAL_API_KEY` lives in Portainer stack 183 env only.

---

## File Structure

**Backend (Java)**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V23__page_text_content_type.sql` | Add + backfill `page_text.content_type` (already written) |
| `backend/src/main/resources/db/migration/V24__text_chunk_heading.sql` | Add `text_chunk.content_type` and `text_chunk.heading` |
| `backend/src/main/java/place/icomb/archiver/service/OcrContentType.java` | `PLAIN` / `MARKDOWN` constants (already written) |
| `backend/src/main/java/place/icomb/archiver/model/PageText.java` | `contentType` field (already written) |
| `backend/src/main/java/place/icomb/archiver/service/MistralOcrWorker.java` | Declares `text/markdown` (already written) |
| `backend/src/main/java/place/icomb/archiver/service/ClaudeOcrWorker.java` | Declares `text/plain` (already written) |
| `backend/src/main/java/place/icomb/archiver/service/QwenOcrWorker.java` | Declares `text/plain` |
| `backend/src/main/java/place/icomb/archiver/dto/OcrResultRequest.java` | Accept `contentType` from Python workers |
| `backend/src/main/java/place/icomb/archiver/controller/ProcessorController.java` | Persist + serve `content_type`; accept chunk `heading` |
| `backend/src/main/java/place/icomb/archiver/controller/ViewerController.java` | Serve `contentType` to the frontend |

**Python (shared)**

| File | Responsibility |
|---|---|
| `worker-common/src/worker_common/markdown.py` | **New.** All markdown handling: plain projection, block split/render, section split |
| `worker-common/tests/test_markdown.py` | **New.** Unit tests for the above |

**Python (consumers)**

| File | Responsibility |
|---|---|
| `pdf-worker/src/pdf_worker/main.py` | Use plain projection for the invisible text layer |
| `translate-worker/src/translate_worker/translator.py` | Block-preserving translation; stop flattening with `" ".join` |
| `translate-worker/src/translate_worker/main.py` | Pass content type through, return it with the result |
| `embed-worker/src/embed_worker/chunker.py` | Heading-aware chunking for markdown |
| `embed-worker/src/embed_worker/main.py` | Send `heading` and `contentType` with each chunk |
| `ocr-worker-qwen3vl/src/ocr_worker_qwen3vl/client.py` | Send `contentType` on OCR submit |

---

## Task 1: Store and serve the OCR media type

Completes the half-finished work already in the tree and makes the type visible end to end.

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__page_text_content_type.sql` *(exists, uncommitted)*
- Create: `backend/src/main/java/place/icomb/archiver/service/OcrContentType.java` *(exists, uncommitted)*
- Modify: `backend/src/main/java/place/icomb/archiver/model/PageText.java` *(exists, uncommitted)*
- Modify: `backend/src/main/java/place/icomb/archiver/service/QwenOcrWorker.java:112`
- Modify: `backend/src/main/java/place/icomb/archiver/dto/OcrResultRequest.java`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/ProcessorController.java:182-190` (insert), `:294-311` (select)
- Modify: `backend/src/main/java/place/icomb/archiver/controller/ViewerController.java:370-390`
- Modify: `ocr-worker-qwen3vl/src/ocr_worker_qwen3vl/client.py:18-29`
- Test: `backend/src/test/java/place/icomb/archiver/service/OcrContentTypeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `OcrContentType.PLAIN` = `"text/plain"`, `OcrContentType.MARKDOWN` = `"text/markdown"`
  - `PageText.getContentType()` / `setContentType(String)`
  - `OcrResultRequest(String engine, Float confidence, String textRaw, String hocr, String contentType)`
  - `GET /api/processor/records/{id}/pages` rows gain key `content_type`
  - `GET /api/pages/{pageId}/text` response gains key `contentType`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/place/icomb/archiver/service/OcrContentTypeTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OcrContentTypeTest {

  @Test
  void exposesTheTwoMediaTypesThePipelineProduces() {
    assertThat(OcrContentType.PLAIN).isEqualTo("text/plain");
    assertThat(OcrContentType.MARKDOWN).isEqualTo("text/markdown");
  }

  @Test
  void bothValuesSatisfyTheDatabaseMediaTypeConstraint() {
    // V23 constrains content_type to ^[a-z]+/[a-z0-9.+-]+$
    assertThat(OcrContentType.PLAIN).matches("^[a-z]+/[a-z0-9.+-]+$");
    assertThat(OcrContentType.MARKDOWN).matches("^[a-z]+/[a-z0-9.+-]+$");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p backend test --tests '*OcrContentTypeTest'`
Expected: compiles and passes if `OcrContentType.java` is already present from the uncommitted work; if the file is missing, FAIL with "cannot find symbol OcrContentType".

- [ ] **Step 3: Set the content type in QwenOcrWorker**

`QwenOcrWorker.java`, immediately after `pt.setEngine("qwen3vl");`:

```java
    pt.setEngine("qwen3vl");
    pt.setContentType(OcrContentType.PLAIN);
```

- [ ] **Step 4: Add contentType to the Python-worker DTO**

`OcrResultRequest.java` — replace the whole record:

```java
package place.icomb.archiver.dto;

/**
 * OCR result posted by an external worker. {@code contentType} is the IANA media type of {@code
 * textRaw}; workers that predate the field send null and are treated as {@code text/plain}.
 */
public record OcrResultRequest(
    String engine, Float confidence, String textRaw, String hocr, String contentType) {}
```

- [ ] **Step 5: Persist it in ProcessorController.submitOcrResult**

Replace the `jdbcTemplate.update(...)` call in `submitOcrResult`:

```java
    jdbcTemplate.update(
        "INSERT INTO page_text (page_id, engine, confidence, text_raw, hocr, content_type,"
            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        pageId,
        request.engine(),
        request.confidence(),
        request.textRaw(),
        request.hocr(),
        request.contentType() != null ? request.contentType() : OcrContentType.PLAIN,
        Timestamp.from(Instant.now()));
```

Add the import: `import place.icomb.archiver.service.OcrContentType;`

- [ ] **Step 6: Serve it to the PDF worker**

In `ProcessorController.getRecordPagesWithText`, add `pt.content_type` to both the outer select list and the lateral subquery:

```java
            SELECT p.id AS page_id, p.seq, p.attachment_id, p.width, p.height,
                   pt.text_raw, pt.text_en, pt.confidence, pt.content_type
            FROM page p
            LEFT JOIN LATERAL (
                SELECT pt2.text_raw, pt2.text_en, pt2.confidence, pt2.content_type
                FROM page_text pt2
                WHERE pt2.page_id = p.id
                ORDER BY pt2.confidence DESC NULLS LAST
                LIMIT 1
            ) pt ON true
            WHERE p.record_id = ?
            ORDER BY p.seq
```

- [ ] **Step 7: Serve it to the frontend and translate worker**

In `ViewerController.getPageText`, after the `engine` entry:

```java
    result.put("engine", best.getEngine() != null ? best.getEngine() : "");
    result.put(
        "contentType",
        best.getContentType() != null ? best.getContentType() : OcrContentType.PLAIN);
```

Add the import: `import place.icomb.archiver.service.OcrContentType;`

Also update the empty-result branch at the top of the method so the key is always present:

```java
    if (texts.isEmpty()) {
      return ResponseEntity.ok(
          Map.of(
              "pageId", pageId,
              "text", "",
              "confidence", 0.0,
              "engine", "",
              "contentType", OcrContentType.PLAIN));
    }
```

- [ ] **Step 8: Send it from the Qwen Python worker**

`ocr-worker-qwen3vl/src/ocr_worker_qwen3vl/client.py` — replace `submit_ocr_result`:

```python
    def submit_ocr_result(
        self,
        page_id: int,
        engine: str,
        confidence: float,
        text_raw: str,
        content_type: str = "text/plain",
    ):
        """POST OCR results for a page.

        content_type is the IANA media type of text_raw. This worker's model returns
        flat text; the backend defaults to text/plain if the field is omitted, so older
        worker builds keep working.
        """
        body = {
            "engine": engine,
            "confidence": confidence,
            "textRaw": text_raw,
            "contentType": content_type,
        }
        resp = self._client.post(f"/api/processor/ocr/{page_id}", json=body)
        resp.raise_for_status()
        return resp.json()
```

- [ ] **Step 9: Verify build and tests**

Run: `./gradlew -p backend spotlessApply compileJava compileTestJava test --tests '*OcrContentTypeTest' --tests '*MistralOcrWorkerResponseTest'`
Expected: BUILD SUCCESSFUL.
Run: `ruff check ocr-worker-qwen3vl/`
Expected: "All checks passed".

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/db/migration/V23__page_text_content_type.sql \
        backend/src/main/java/place/icomb/archiver/service/OcrContentType.java \
        backend/src/main/java/place/icomb/archiver/model/PageText.java \
        backend/src/main/java/place/icomb/archiver/service/QwenOcrWorker.java \
        backend/src/main/java/place/icomb/archiver/service/MistralOcrWorker.java \
        backend/src/main/java/place/icomb/archiver/service/ClaudeOcrWorker.java \
        backend/src/main/java/place/icomb/archiver/dto/OcrResultRequest.java \
        backend/src/main/java/place/icomb/archiver/controller/ProcessorController.java \
        backend/src/main/java/place/icomb/archiver/controller/ViewerController.java \
        backend/src/test/java/place/icomb/archiver/service/OcrContentTypeTest.java \
        ocr-worker-qwen3vl/src/ocr_worker_qwen3vl/client.py
git commit -m "feat: record the media type of every OCR transcription"
```

---

## Task 2: Shared markdown module in worker-common

Every Python consumer needs the same three operations. They live in one place so a new engine or a new consumer never re-implements them.

**Files:**
- Create: `worker-common/src/worker_common/markdown.py`
- Create: `worker-common/tests/test_markdown.py`

**Interfaces:**
- Consumes: `content_type` strings from Task 1.
- Produces:
  - `PLAIN = "text/plain"`, `MARKDOWN = "text/markdown"`
  - `to_plain_text(text: str, content_type: str) -> str`
  - `Block` dataclass: `kind: str`, `prefix: str`, `text: str`, `suffix: str`
  - `parse_blocks(text: str, content_type: str) -> list[Block]`
  - `render_blocks(blocks: list[Block]) -> str`
  - `iter_sections(text: str, content_type: str) -> list[tuple[str, str]]` — `(heading_path, body)`

- [ ] **Step 1: Write the failing tests**

`worker-common/tests/test_markdown.py`:

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd worker-common && PYTHONPATH=src python3 -m pytest tests/test_markdown.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'worker_common.markdown'`

- [ ] **Step 3: Write the implementation**

`worker-common/src/worker_common/markdown.py`:

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd worker-common && PYTHONPATH=src python3 -m pytest tests/test_markdown.py -q`
Expected: 11 passed.
Run: `ruff check worker-common/ && ruff format --check worker-common/src/worker_common/markdown.py`
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add worker-common/src/worker_common/markdown.py worker-common/tests/test_markdown.py
git commit -m "feat: shared markdown handling for the python workers"
```

---

## Task 3: PDF text layer uses the plain projection

Smallest consumer, lowest risk, and it removes the only way the engine switch could visibly damage an artefact.

**Files:**
- Modify: `pdf-worker/src/pdf_worker/main.py:31`
- Test: `pdf-worker/tests/test_text_layer.py`

**Interfaces:**
- Consumes: `worker_common.markdown.to_plain_text`, and the `content_type` key from Task 1's `/api/processor/records/{id}/pages`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

`pdf-worker/tests/test_text_layer.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd pdf-worker && PYTHONPATH=src:../worker-common/src python3 -m pytest tests/test_text_layer.py -q`
Expected: FAIL — `ModuleNotFoundError` until Task 2 is installed, or assertion failure.

- [ ] **Step 3: Use the projection in the worker**

`pdf-worker/src/pdf_worker/main.py` — add the import at the top:

```python
from worker_common.markdown import to_plain_text
```

Replace line 31:

```python
        text = to_plain_text(
            pm.get("text_raw", "") or "", pm.get("content_type") or "text/plain"
        )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd pdf-worker && PYTHONPATH=src:../worker-common/src python3 -m pytest tests/ -q`
Expected: all pass.
Run: `ruff check pdf-worker/`
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add pdf-worker/src/pdf_worker/main.py pdf-worker/tests/test_text_layer.py
git commit -m "fix: keep markdown markers out of the searchable PDF text layer"
```

---

## Task 4: Markdown-preserving translation

Also fixes a pre-existing defect: translation currently flattens every page to a single line, because each chunk is `.strip()`ed and the results rejoined with `" ".join(...)`. Section headings are swallowed into the body text.

**Files:**
- Modify: `translate-worker/src/translate_worker/translator.py:169-217`
- Modify: `translate-worker/src/translate_worker/main.py:33-56`
- Test: `translate-worker/tests/test_translate_structure.py`

**Interfaces:**
- Consumes: `worker_common.markdown.parse_blocks`, `render_blocks`; `contentType` from `GET /api/pages/{pageId}/text`.
- Produces: `Translator.translate(text, source_lang=None, target_lang="en", content_type="text/plain") -> str`.

- [ ] **Step 1: Write the failing test**

`translate-worker/tests/test_translate_structure.py`:

```python
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
```

Note the `- 5 -` case: as plain text it is one paragraph, so it is translated as content, not treated as a list marker.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd translate-worker && PYTHONPATH=src:../worker-common/src python3 -m pytest tests/test_translate_structure.py -q`
Expected: FAIL until Task 2 is on the path.

- [ ] **Step 3: Rewrite `Translator.translate` to be block-aware**

`translate-worker/src/translate_worker/translator.py` — add the import:

```python
from worker_common.markdown import parse_blocks, render_blocks
```

Replace the body of `translate` from `chunks = self._split_chunks(text)` to `return result` with:

```python
        blocks = parse_blocks(text, content_type)
        for block in blocks:
            if block.kind == "table_divider" or not block.text.strip():
                continue
            # Long blocks still need chunking: MarianMT truncates at 512 tokens.
            pieces = self._split_chunks(block.text)
            block.text = " ".join(
                self._translate_chunk(p, tokenizer, model) for p in pieces if p.strip()
            )
        result = render_blocks(blocks)
```

and change the signature to:

```python
    def translate(
        self,
        text: str,
        source_lang: str | None = None,
        target_lang: str = "en",
        content_type: str = "text/plain",
    ) -> str:
```

- [ ] **Step 4: Pass the content type through the worker**

`translate-worker/src/translate_worker/main.py`, in `process_translate_page`, replace the fetch-and-translate section:

```python
    page_data = client.get_page_text(page_id)
    text = page_data.get("text") or ""
    content_type = page_data.get("contentType") or "text/plain"

    if not text.strip():
        log.info("  Page %d has no text, skipping translation", page_id)
        client.complete_job(job_id)
        return

    translated = translator.translate(text, source_lang=source_lang, content_type=content_type)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd translate-worker && PYTHONPATH=src:../worker-common/src python3 -m pytest tests/ -q`
Expected: all pass.
Run: `ruff check translate-worker/`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add translate-worker/src/translate_worker/translator.py \
        translate-worker/src/translate_worker/main.py \
        translate-worker/tests/test_translate_structure.py
git commit -m "fix: preserve document structure through translation"
```

---

## Task 5: Heading-aware chunking, Qwen3 embeddings, halfvec storage

These are one task because they share one re-embed. Heading-aware chunking changes
chunk *content*, the model change invalidates every vector, and the column type
changes how vectors are stored — doing them separately would mean paying for three
full re-embeds of 87,338 chunks instead of one.

### Why these choices (measured, not assumed)

Benchmarked on the archive's own chunks: 400 English research questions against
4,000 real German chunks, and 150 against 200 Czech-bearing chunks. Every retrieval
in this system is cross-lingual, because `embed-worker` embeds `text_raw` (German or
Czech OCR) while users query in English.

| model @1024 | German R@1 | Czech R@1 |
|---|---|---|
| **Qwen/Qwen3-Embedding-8B** | **0.818** | **0.920** |
| nvidia/Nemotron-3-Embed-1B | 0.868 | 0.733 |
| google/embeddinggemma-300m | 0.743 | 0.820 |
| BAAI/bge-m3 *(current)* | 0.650 | 0.827 |
| intfloat/multilingual-e5-large-instruct | 0.412 | 0.820 |

Qwen3 is the only candidate that beats the incumbent in **both** languages. Nemotron
scores best on German but drops Czech below what BGE-M3 already achieves, and Czech
is 11.7% of the index (10,241 of 87,338 chunks) — mostly *inside* records labelled
`lang='de'`, because Protectorate administration was bilingual. `record.lang` does
not describe page content.

Truncation to 1024 costs nothing. Qwen3-Embedding is Matryoshka-trained, so a prefix
of the vector is itself a valid embedding:

| dim | German R@1 | Czech R@1 |
|---|---|---|
| 4096 (native, unindexable) | 0.812 | 0.913 |
| 2000 | 0.828 | 0.920 |
| 1024 | 0.820 | 0.920 |

Full size is marginally *worse* than truncated on both, well inside noise. 1024 stays
under pgvector's 2,000-dimension HNSW ceiling, so no index gymnastics are needed.

`halfvec` (fp16) was measured on these vectors: **identical** R@1/R@5/MRR to fp32, max
per-component error 0.000121. It halves the vector bytes, and the rows are being
rewritten anyway.

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__chunk_heading_and_halfvec.sql`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/ProcessorController.java` (`storeEmbeddings`)
- Modify: `backend/src/main/java/place/icomb/archiver/controller/SemanticSearchController.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `embed-worker/src/embed_worker/chunker.py`
- Modify: `embed-worker/src/embed_worker/main.py`
- Modify: `embed-worker/src/embed_worker/client.py`
- Test: `embed-worker/tests/test_heading_chunks.py`

**Interfaces:**
- Consumes: `worker_common.markdown.iter_sections` (Task 2).
- Produces:
  - `chunk_document(text, content_type, max_chars=2000, overlap=200) -> list[dict]`
    with keys `content` and `heading`
  - `POST /api/processor/embeddings` chunk objects accept optional `heading`
  - `archiver.embed.*` config gains `model`, `dimensions`, `query-prefix`

### The trap in this task

**Two components embed text and they must agree.** `embed-worker` embeds passages;
`SemanticSearchController.embedText()` embeds the search query. Qwen3 wants an
instruction prefix on the **query side only** — passages get none. If the two drift
apart (different model, or prefix applied to both/neither) retrieval quality collapses
with no error anywhere. Both read the same `archiver.embed.*` config for that reason.

- [ ] **Step 1: Write the failing test**

`embed-worker/tests/test_heading_chunks.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `PYTHONPATH=embed-worker/src:worker-common/src python3 -m pytest embed-worker/tests/test_heading_chunks.py -q`
Expected: FAIL — `ImportError: cannot import name 'chunk_document'`

- [ ] **Step 3: Add `chunk_document`**

Append to `embed-worker/src/embed_worker/chunker.py`, leaving `chunk_text` alone (it
still serves the record-metadata chunk):

```python
from worker_common.markdown import iter_sections


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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `PYTHONPATH=embed-worker/src:worker-common/src python3 -m pytest embed-worker/tests/test_heading_chunks.py -q`
Expected: 4 passed.

- [ ] **Step 5: Write the migration**

`backend/src/main/resources/db/migration/V24__chunk_heading_and_halfvec.sql`:

```sql
-- Chunks are regenerated wholesale by the re-embed that follows this migration:
-- heading-aware chunking changes their content and boundaries, and the embedding
-- model changes from BGE-M3 to Qwen3-Embedding-8B. Nothing here is worth
-- preserving, so the column is replaced rather than converted.
--
-- halfvec (fp16) was measured against fp32 on this archive's own vectors:
-- identical R@1/R@5/MRR, max per-component error 0.000121. It halves the bytes.
--
-- The HNSW index is deliberately NOT recreated here. Building it after the bulk
-- load is much faster than maintaining it across 87k inserts, so it is a manual
-- post-step once re-embedding is complete.

DROP INDEX IF EXISTS idx_text_chunk_embedding;

ALTER TABLE text_chunk
    DROP COLUMN embedding,
    ADD COLUMN embedding halfvec(1024),
    ADD COLUMN heading text NOT NULL DEFAULT '',
    ADD COLUMN content_type text NOT NULL DEFAULT 'text/plain';

ALTER TABLE text_chunk
    ADD CONSTRAINT text_chunk_content_type_check
    CHECK (content_type ~ '^[a-z]+/[a-z0-9.+-]+$');

COMMENT ON COLUMN text_chunk.heading IS
    'Markdown heading path of the section this chunk came from, empty for plain text.';
```

- [ ] **Step 6: Persist the heading and cast to halfvec**

In `ProcessorController.storeEmbeddings`, inside the chunk loop:

```java
      String content = (String) chunk.get("content");
      String heading = chunk.get("heading") != null ? (String) chunk.get("heading") : "";
```

and the insert:

```java
      jdbcTemplate.update(
          "INSERT INTO text_chunk (record_id, page_id, chunk_index, content, heading, embedding,"
              + " created_at) VALUES (?, ?, ?, ?, ?, ?::halfvec, now())",
          recordId,
          pageId,
          chunkIndex,
          content,
          heading,
          vecStr.toString());
```

- [ ] **Step 7: Point the embed worker at Qwen3**

`embed-worker/src/embed_worker/main.py` — replace the page-chunking loop:

```python
    for page in pages:
        page_id = page.get("page_id")
        text = page.get("text_raw") or page.get("text_en") or ""
        if not text.strip():
            skipped += 1
            continue

        content_type = page.get("content_type") or "text/plain"
        for i, chunk in enumerate(chunk_document(text, content_type)):
            all_chunks.append(
                {
                    "page_id": page_id,
                    "chunk_index": i,
                    "content": chunk["content"],
                    "heading": chunk["heading"],
                }
            )
```

Change the import to `from .chunker import chunk_document, chunk_text`, and add
`"heading": c.get("heading", "")` to each dict in `chunks_payload`.

In `embed_batch`, send the model and dimensions, and use the OpenAI-compatible
endpoint. DeepInfra honours `dimensions` server-side (verified: returns 1024 rather
than 4096), so the truncation costs no bandwidth:

```python
        resp = httpx.post(
            f"{tei_url}/embeddings",
            headers={"Authorization": f"Bearer {tei_key}"},
            json={"model": model, "input": texts, "dimensions": dimensions},
            timeout=120.0,
        )
```

Passages get **no** prefix — the instruction goes on the query side only.

- [ ] **Step 8: Match the query side in the backend**

`SemanticSearchController` must embed queries with the same model and Qwen3's
instruction prefix, and cast to `halfvec`. In `application.yml`:

```yaml
  embed:
    tei-url: ${EMBED_TEI_URL:}
    tei-key: ${EMBED_TEI_KEY:}
    model: ${EMBED_MODEL:Qwen/Qwen3-Embedding-8B}
    dimensions: ${EMBED_DIMENSIONS:1024}
    query-prefix: ${EMBED_QUERY_PREFIX:"Instruct: Given a research question, retrieve the archive passage that answers it\nQuery: "}
```

In `embedText`, prepend `queryPrefix` to the query and send `model`/`dimensions`.
Change every `?::vector` in this controller to `?::halfvec` (there is one in the
hybrid search SQL at the `sem_score` line).

- [ ] **Step 9: Verify**

Run: `PYTHONPATH=embed-worker/src:worker-common/src python3 -m pytest embed-worker/tests/ -q`
Run: `./gradlew -p backend spotlessApply compileJava compileTestJava`
Run: `ruff check embed-worker/`
Expected: all pass.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/db/migration/V24__chunk_heading_and_halfvec.sql \
        backend/src/main/java/place/icomb/archiver/controller/ProcessorController.java \
        backend/src/main/java/place/icomb/archiver/controller/SemanticSearchController.java \
        backend/src/main/resources/application.yml \
        embed-worker/src/embed_worker/chunker.py \
        embed-worker/src/embed_worker/main.py \
        embed-worker/src/embed_worker/client.py \
        embed-worker/tests/test_heading_chunks.py
git commit -m "feat: heading-aware chunks, Qwen3 embeddings, halfvec storage"
```

---

## Task 7: Replace MarianMT with an LLM translator

MarianMT goes entirely. No fallback is retained — the decision is forwards only.

### Why (measured on 12 real Mistral-OCR markdown pages)

| model | digits kept | names kept | headings | preamble | $/page |
|---|---|---|---|---|---|
| **google/gemma-4-31B-it** | **1.000** | **1.000** | 1.000 | 0 | $0.000216 |
| deepseek-ai/DeepSeek-V3.2 | 0.828 | 1.000 | 1.000 | 0 | $0.000320 |
| meta-llama/Llama-4-Scout | 0.806 | 1.000 | 1.000 | 0 | $0.000161 |
| **MarianMT (incumbent)** | 0.750 | 1.000 | n/a | n/a | GPU |
| mistralai/Mistral-Small-3.2 | 0.528 | 0.944 | 0.917 | 2 | $0.000110 |

`digits kept` compares digit sequences with thousands separators normalised, because
German "35.000" correctly becomes English "35,000" — scoring those as a loss made
every model look far worse than it is.

Quality difference on the passage that matters, `jener Schicht Intellektueller
Führungskräfte angehören`:

- MarianMT: "belonged to a number of other **classes of intellectual leaders**" — wrong
- gemma-4: "belong to **that stratum of intellectual leaders**" — right

Markdown survives natively: `# Der Reichsprotektor` becomes `# The Reich Protector`,
same level, same line count, no preamble, no code fences.

Whole archive at gemma-4 rates: roughly **$26** for 120,386 pages.

### What this deletes

- torch, transformers, sentencepiece and the CUDA base layer from the image
- `runtime: nvidia`, `NVIDIA_VISIBLE_DEVICES`, `HF_HOME` and the model cache
- the LRU model-cache eviction added when the writable HF cache let sixteen models
  become resident and exhaust the A2000
- langdetect and the whole missing-language-pair failure class — nineteen jobs are
  currently poisoned asking for `opus-mt-sl-en`, `-sw-en`, `-no-en`, `-ro-en`, `-pt-en`,
  none of which Helsinki ever published
- roughly 5GB of VRAM on the shared A2000

`worker_common.markdown` stays — pdf-worker still needs `to_plain_text` and embed-worker
still needs `iter_sections`. Only the translator's use of `parse_blocks`/`render_blocks`
goes, because an LLM preserves structure without being told how.

**Files:**
- Modify: `translate-worker/src/translate_worker/translator.py` (rewrite)
- Modify: `translate-worker/src/translate_worker/config.py`
- Modify: `translate-worker/src/translate_worker/main.py`
- Modify: `translate-worker/Dockerfile`
- Modify: `translate-worker/pyproject.toml`
- Modify: `deploy/docker-compose.yml`
- Test: `translate-worker/tests/test_llm_translator.py`

**Interfaces:**
- Produces: `Translator.translate(text, source_lang=None, target_lang="en",
  content_type="text/plain") -> str` — signature unchanged, so `main.py` barely moves.

### Traps

**The output must be the translation and nothing else.** Two of the five models tested
prefixed a chat preamble ("Here is the faithful translation…"). That would be stored
verbatim in `text_en`. Strip a leading preamble line and any wrapping code fence, and
assert their absence in tests.

**Do not let the model correct the source.** These are OCR transcriptions of damaged
documents; an LLM will happily tidy them. The prompt says translate what is there.

**Keep it faithful.** The material is Nazi administrative record. A model that softens
or declines produces a useless archive. Verified in the benchmark: names, figures and
the substance all came through intact.

- [ ] **Step 1: Write the failing test** — see the plan's sibling tasks for the shape;
  cover: markdown heading survives as a heading, no preamble in output, no code fence,
  numbers preserved, and that a plain-text page is not turned into markdown.

- [ ] **Step 2: Rewrite `translator.py`** against an OpenAI-compatible chat endpoint,
  configured by `TRANSLATE_BASE_URL`, `TRANSLATE_API_KEY`, `TRANSLATE_MODEL`
  (default `google/gemma-4-31B-it`). One request per page; a page is ~2000 characters,
  far inside context, so no chunking is needed.

- [ ] **Step 3: Strip the image down** — remove torch/transformers/sentencepiece from
  `pyproject.toml` and the CUDA layers from the `Dockerfile`; the worker now needs only
  httpx and worker-common.

- [ ] **Step 4: Drop the GPU wiring** from `deploy/docker-compose.yml` — `runtime: nvidia`,
  `NVIDIA_VISIBLE_DEVICES`, `HF_HOME`, `HOME`, `LOGNAME` — and add the new config.

- [ ] **Step 5: Verify** — `pytest translate-worker/tests/ -q`, `ruff check translate-worker/`.

- [ ] **Step 6: Commit.**

---

## Task 6: Deploy, then hand back

**The re-OCR pipeline is never run by the implementer.** No resetting records to
`ocr_pending`, no enqueuing `ocr_page_*` jobs, no `reset-pipeline` calls. Re-processing
costs real money across 128,484 pages and the timing, scope and engine are the user's
call. Deploy the code, verify against data that already exists, and stop.

**Files:** none — deployment and non-destructive verification only.

**Interfaces:** consumes everything above.

- [ ] **Step 1: Push and wait for CI**

```bash
git push origin main
jk run ls archiver | head -2
```

Wait for SUCCESS: `jk run view archiver <n> --wait`.
Note: changes under `worker-common/` rebuild every worker image, so expect ~15 min.

- [ ] **Step 2: Redeploy and confirm migrations applied**

Trigger the stack webhook, then:

```bash
ssh zelkova "docker logs archiver-backend-1 2>&1 | grep -E 'Successfully applied|Migrating to version|Registered .* OCR worker' | tail -8"
```

Expected: V24 applied (V23 too if it had not already), workers registered.

- [ ] **Step 3: Confirm the backfill is correct on existing data**

```bash
PGPASSWORD=archiver psql -h 192.168.19.130 -U archiver -d archiver \
  -c "select engine, content_type, count(*) from page_text group by 1,2 order by 3 desc;"
```

Expected: `mistral-ocr | text/markdown`; every other engine `text/plain`. No nulls.
This reads existing rows — it processes nothing.

- [ ] **Step 4: Verify the plain projection against a real stored markdown page**

Read one already-OCR'd markdown page out of the database and run it through the shared
helper locally. No pipeline involved.

```bash
PGPASSWORD=archiver psql -h 192.168.19.130 -U archiver -d archiver -t -A -c \
  "select text_raw from page_text where content_type='text/markdown' limit 1;" > /tmp/md_page.txt

cd worker-common && PYTHONPATH=src python3 -c "
from worker_common.markdown import to_plain_text, iter_sections, MARKDOWN
t = open('/tmp/md_page.txt').read()
plain = to_plain_text(t, MARKDOWN)
assert '#' not in plain, 'heading marker leaked into plain projection'
print('plain projection clean,', len(plain), 'chars')
print('sections:', [h for h, _ in iter_sections(t, MARKDOWN)][:5])
"
```

Expected: clean projection, section headings listed.

- [ ] **Step 5: Report and hand back**

State plainly: what deployed, which migrations applied, what was verified, and that
**no records were re-processed**. List for the user what a re-run would cover
(engine, page count, estimated cost) so they can decide when and whether to run it.

---

## Notes for the executor

**Never run any re-OCR or re-embed.** Not the 66,796-page paddle backlog, not a single
record, not "just to check". The user runs re-processing manually once this has landed.
The reason the chunker work is in this plan at all is so that when they do run it, they
pay for re-OCR and re-embed once rather than twice.

**Database backup** taken 2026-09-08 08:02 UTC at `/Volumes/External/Projects/archiver-backups/archiver-20260908-080222.dump` (594 MB, verified). Take a fresh one before running V24 if significant time has passed.

**Pre-existing test debt:** `translate_worker/main.py` and `translator.py` fail `ruff format --check` today, before any change in this plan. Do not reformat them wholesale — it buries the diff. Format only the lines you touch.
