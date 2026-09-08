"""LLM-based translation via an OpenAI-compatible chat completions endpoint.

Replaces the previous MarianMT (Helsinki-NLP) implementation. One HTTP request
per page/field — a page is roughly 2000 characters, far inside any modern
context window, so no chunking is needed. Markdown structure (headings,
tables, list items, paragraph breaks) survives because the model is told to
preserve it, not because the text is split into blocks and reassembled.
"""

import logging
import re
import time

import httpx

log = logging.getLogger(__name__)

# ISO 639-1 -> the language name used in the prompt. Extend as new source
# languages show up in the archive; an unlisted code falls back to omitting
# the language name entirely rather than guessing.
_LANGUAGE_NAMES = {
    "de": "German",
    "cs": "Czech",
    "sk": "Slovak",
    "pl": "Polish",
    "hu": "Hungarian",
    "fr": "French",
    "it": "Italian",
    "ru": "Russian",
}

_MARKDOWN_RULE = (
    "- The input is Markdown. Return Markdown with the identical structure: same "
    "headings at the same levels, same tables, same list items, same paragraph "
    "breaks."
)

_PLAIN_TEXT_RULE = (
    "- The input is plain text. Preserve the plain-text line layout exactly as "
    "given. Do not introduce Markdown formatting (no headings, no bullet lists, "
    "no bold/italic markers)."
)

_PROMPT_TEMPLATE = """Translate the following{lang_clause} archival document into English.

It is a 1942 administrative report and may contain OCR errors — translate what is
there, do not correct or embellish it.

Rules:
{structure_rule}
- Keep every name, date and number exactly as written.
- Translate faithfully. Do not summarise, soften, censor or omit anything.
- Output ONLY the translation. No preamble, no commentary, no code fences.

DOCUMENT:

{text}"""

# A code fence wrapping the *entire* response, e.g. because the model treated
# the markdown instruction as "put it in a code block".
_CODE_FENCE_RE = re.compile(r"^```[a-zA-Z]*\n(.*)\n```$", re.DOTALL)

# Cues seen in benchmarked chat preambles ("Here is the faithful
# translation:", "Certainly, here's the translation of the document:").
# Matched against the first line only, case-insensitively.
_PREAMBLE_MARKERS = (
    "here is",
    "here's",
    "certainly",
    "sure,",
    "translation of",
    "translated text",
    "translated version",
    "below is",
)


def _language_clause(source_lang: str | None) -> str:
    """Return e.g. ' German' for use in 'Translate the following{clause} ...'.

    Empty string when the source language is unknown, so the sentence still
    reads naturally without guessing at a language.
    """
    if not source_lang:
        return ""
    name = _LANGUAGE_NAMES.get(source_lang.lower())
    return f" {name}" if name else ""


def _build_prompt(text: str, source_lang: str | None, content_type: str) -> str:
    structure_rule = _MARKDOWN_RULE if content_type == "text/markdown" else _PLAIN_TEXT_RULE
    return _PROMPT_TEMPLATE.format(
        lang_clause=_language_clause(source_lang),
        structure_rule=structure_rule,
        text=text,
    )


def _strip_preamble(text: str) -> str:
    """Drop a leading chat preamble line, e.g. 'Here is the translation:'."""
    newline = text.find("\n")
    first_line = text if newline == -1 else text[:newline]
    stripped_first = first_line.strip()
    lowered = stripped_first.lower()

    looks_like_structure = stripped_first.startswith(("#", "-", "*", "|", "```", ">"))
    looks_like_preamble = stripped_first and not looks_like_structure and (
        lowered.endswith(":") or any(marker in lowered for marker in _PREAMBLE_MARKERS)
    )

    if not looks_like_preamble:
        return text

    remainder = "" if newline == -1 else text[newline + 1 :]
    return remainder.lstrip("\n")


def _strip_code_fence(text: str) -> str:
    """Drop a code fence wrapping the whole response."""
    stripped = text.strip()
    match = _CODE_FENCE_RE.match(stripped)
    return match.group(1) if match else text


def _clean_response(raw: str) -> str:
    text = raw.strip()
    text = _strip_preamble(text).strip()
    text = _strip_code_fence(text).strip()
    return text


class Translator:
    """Translates text to English via an OpenAI-compatible chat endpoint."""

    def __init__(self, base_url: str, api_key: str, model: str):
        self._model = model
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=120.0,
        )
        log.info("Translator initialized (base_url=%s, model=%s)", base_url, model)

    def close(self) -> None:
        self._client.close()

    #: Languages offered by the on-demand translation UI.
    #:
    #: The LLM translates between any of these on demand, so this is a menu rather than a
    #: capability list — it replaced the MarianMT era's fixed set of downloaded model pairs.
    #: Scoped to the languages this archive actually contains: Habsburg and Protectorate
    #: administration (German, Czech, Slovak, Hungarian, Polish), the surrounding region, the
    #: Latin of parish registers, and the languages of Holocaust documentation.
    SUPPORTED_LANGUAGES: list[dict[str, str]] = [
        {"code": "de", "name": "Deutsch"},
        {"code": "cs", "name": "Čeština"},
        {"code": "en", "name": "English"},
        {"code": "sk", "name": "Slovenčina"},
        {"code": "pl", "name": "Polski"},
        {"code": "hu", "name": "Magyar"},
        {"code": "fr", "name": "Français"},
        {"code": "it", "name": "Italiano"},
        {"code": "nl", "name": "Nederlands"},
        {"code": "ru", "name": "Русский"},
        {"code": "uk", "name": "Українська"},
        {"code": "ro", "name": "Română"},
        {"code": "sl", "name": "Slovenščina"},
        {"code": "hr", "name": "Hrvatski"},
        {"code": "la", "name": "Latina"},
        {"code": "yi", "name": "ייִדיש"},
        {"code": "he", "name": "עברית"},
    ]

    def supported_languages(self) -> list[dict[str, str]]:
        """Languages offered by the translation UI. Any of them may be source or target."""
        return list(self.SUPPORTED_LANGUAGES)

    def _call_llm(self, prompt: str) -> str:
        response = self._client.post(
            "/chat/completions",
            json={
                "model": self._model,
                "max_tokens": 3000,
                "temperature": 0.1,
                "messages": [{"role": "user", "content": prompt}],
            },
        )
        response.raise_for_status()
        data = response.json()
        return data["choices"][0]["message"]["content"]

    def translate(
        self,
        text: str,
        source_lang: str | None = None,
        target_lang: str = "en",
        content_type: str = "text/plain",
    ) -> str:
        """Translate text to `target_lang` (default English).

        Args:
            text: The source text to translate.
            source_lang: ISO 639-1 code, used only to name the source
                language in the prompt. Unknown/absent languages are still
                translated; the prompt just omits the language name.
            target_lang: ISO 639-1 code for the target language (default: 'en').
            content_type: Media type of `text` ("text/markdown" or
                "text/plain"). Determines whether the prompt asks the model
                to preserve Markdown structure or plain-text line layout.

        Returns:
            Translated text, or the original if source == target.
        """
        if not text or not text.strip():
            return ""

        if source_lang == target_lang:
            log.info("Source == target (%s, %d chars), skipping", source_lang, len(text))
            return text

        t0 = time.monotonic()
        prompt = _build_prompt(text, source_lang, content_type)
        raw = self._call_llm(prompt)
        result = _clean_response(raw)
        elapsed = time.monotonic() - t0

        log.info(
            "Translated %d chars -> %d chars (lang=%s, model=%s) in %.1fs",
            len(text),
            len(result),
            source_lang or "auto",
            self._model,
            elapsed,
        )

        return result
