"""LLM translator: prompt shape, request payload, and output hygiene.

MarianMT is gone. Translation is now one OpenAI-compatible chat completion
per page, with the HTTP call mocked via respx so tests never hit a network.
"""

import json

import httpx
import respx

from translate_worker.translator import Translator


def _translator(**kwargs) -> Translator:
    defaults = dict(
        base_url="https://api.deepinfra.com/v1/openai",
        api_key="test-key",
        model="google/gemma-4-31B-it",
    )
    defaults.update(kwargs)
    return Translator(**defaults)


def _mock_chat(content: str):
    return respx.post("https://api.deepinfra.com/v1/openai/chat/completions").mock(
        return_value=httpx.Response(
            200, json={"choices": [{"message": {"content": content}}]}
        )
    )


@respx.mock
def test_sends_openai_compatible_chat_request():
    route = _mock_chat("# The Reich Protector")
    t = _translator()

    result = t.translate("# Der Reichsprotektor", source_lang="de", content_type="text/markdown")

    assert result == "# The Reich Protector"
    assert route.called
    request = route.calls[0].request
    assert request.headers["Authorization"] == "Bearer test-key"
    body = json.loads(request.content)
    assert body["model"] == "google/gemma-4-31B-it"
    assert body["max_tokens"] == 3000
    assert body["temperature"] == 0.1
    assert len(body["messages"]) == 1
    assert body["messages"][0]["role"] == "user"
    assert "Der Reichsprotektor" in body["messages"][0]["content"]


@respx.mock
def test_markdown_heading_survives_and_prompt_mentions_markdown():
    route = _mock_chat("# Enteignung")
    t = _translator()

    result = t.translate("# Enteignung", source_lang="de", content_type="text/markdown")

    assert result == "# Enteignung"
    prompt = route.calls[0].request.content.decode()
    assert "Markdown" in prompt
    assert "German" in prompt


@respx.mock
def test_plain_text_prompt_forbids_introducing_markdown():
    _mock_chat("plain output")
    t = _translator()

    t.translate("plain input", source_lang="de", content_type="text/plain")

    request = respx.calls[0].request
    prompt = request.content.decode()
    assert "plain text" in prompt.lower()
    assert "Do not introduce Markdown" in prompt


@respx.mock
def test_unknown_source_lang_omits_language_name():
    _mock_chat("output")
    t = _translator()

    t.translate("some text", source_lang=None, content_type="text/plain")

    prompt = respx.calls[0].request.content.decode()
    assert "Translate the following archival document" in prompt


@respx.mock
def test_numbers_and_names_pass_through_untouched():
    route = _mock_chat("Against 10 of the most important Czech nobles, Karl Czernin")
    t = _translator()

    result = t.translate(
        "Gegen 10 der wichtigsten tschechischen Adligen, Karl Czernin",
        source_lang="de",
    )

    assert "10" in result
    assert "Karl Czernin" in result
    assert route.called


@respx.mock
def test_strips_leading_chat_preamble():
    _mock_chat("Here is the faithful translation:\n\n# Enteignung\n\nBody text.")
    t = _translator()

    result = t.translate("# Enteignung\n\nKörper.", source_lang="de", content_type="text/markdown")

    assert result == "# Enteignung\n\nBody text."
    assert "Here is" not in result
    assert "faithful" not in result


@respx.mock
def test_strips_wrapping_code_fence():
    _mock_chat("```markdown\n# Enteignung\n\nBody text.\n```")
    t = _translator()

    result = t.translate("# Enteignung\n\nKörper.", source_lang="de", content_type="text/markdown")

    assert result == "# Enteignung\n\nBody text."
    assert "```" not in result


@respx.mock
def test_strips_preamble_and_code_fence_together():
    _mock_chat("Certainly, here's the translation:\n```\nHello world\n```")
    t = _translator()

    result = t.translate("Hallo Welt", source_lang="de")

    assert result == "Hello world"
    assert "Certainly" not in result
    assert "```" not in result


def test_source_equals_target_skips_translation_without_network():
    t = _translator()

    result = t.translate("already english", source_lang="en", target_lang="en")

    assert result == "already english"


def test_empty_text_returns_empty_without_network():
    t = _translator()

    assert t.translate("", source_lang="de") == ""
    assert t.translate("   ", source_lang="de") == ""
