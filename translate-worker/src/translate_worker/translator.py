"""MarianMT translation using Helsinki-NLP models."""

import logging
import re
import time

import torch
from langdetect import detect, LangDetectException
from transformers import MarianMTModel, MarianTokenizer

log = logging.getLogger(__name__)

# Model naming convention: Helsinki-NLP/opus-mt-{src}-{tgt}
MODEL_PREFIX = "Helsinki-NLP/opus-mt-"

# MarianMT has a max token limit of 512; chunk text conservatively at ~400 chars
MAX_CHUNK_CHARS = 400


class Translator:
    """Translates German and Czech text to English using MarianMT."""

    def __init__(self):
        self._models: dict[str, MarianMTModel] = {}
        self._tokenizers: dict[str, MarianTokenizer] = {}
        self._device = "cuda" if torch.cuda.is_available() else "cpu"
        log.info("Translator initialized (device=%s)", self._device)

    def _load_model(self, model_name: str) -> tuple[MarianTokenizer, MarianMTModel]:
        """Lazily load and cache a model + tokenizer."""
        if model_name not in self._models:
            log.info("Loading model %s...", model_name)
            t0 = time.monotonic()
            tokenizer = MarianTokenizer.from_pretrained(model_name)
            model = MarianMTModel.from_pretrained(model_name).to(self._device)
            model.eval()
            elapsed = time.monotonic() - t0
            log.info("Model %s loaded in %.1fs", model_name, elapsed)
            self._tokenizers[model_name] = tokenizer
            self._models[model_name] = model
        return self._tokenizers[model_name], self._models[model_name]

    def available_pairs(self) -> list[tuple[str, str]]:
        """Return (src, tgt) pairs for all locally cached opus-mt models."""
        import os

        hf_home = os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface"))
        hub_dir = os.path.join(hf_home, "hub")
        pairs = []
        if not os.path.isdir(hub_dir):
            return pairs
        for name in os.listdir(hub_dir):
            if name.startswith("models--Helsinki-NLP--opus-mt-"):
                suffix = name.replace("models--Helsinki-NLP--opus-mt-", "")
                parts = suffix.split("-")
                if len(parts) == 2:
                    pairs.append((parts[0], parts[1]))
        return sorted(pairs)

    def detect_language(self, text: str) -> str:
        """Detect source language from text."""
        try:
            lang = detect(text)
            if lang in ("sk",):
                return "cs"
            return lang
        except LangDetectException:
            return "de"

    def _get_model_name(self, source_lang: str, target_lang: str) -> str:
        """Return the model name for a source→target pair."""
        return f"{MODEL_PREFIX}{source_lang}-{target_lang}"

    def _split_chunks(self, text: str) -> list[str]:
        """Split text into chunks of ~MAX_CHUNK_CHARS at sentence/paragraph boundaries."""
        if len(text) <= MAX_CHUNK_CHARS:
            return [text]

        chunks = []
        current = ""

        # Split on paragraph boundaries first, then sentences
        paragraphs = re.split(r"(\n\s*\n)", text)

        for part in paragraphs:
            # If adding this paragraph would exceed the limit, try splitting by sentences
            if len(current) + len(part) > MAX_CHUNK_CHARS and current:
                if len(current.strip()) > 0:
                    chunks.append(current.strip())
                current = ""

            if len(part) <= MAX_CHUNK_CHARS:
                current += part
            else:
                # Split long paragraph by sentences
                sentences = re.split(r"(?<=[.!?])\s+", part)
                for sentence in sentences:
                    if len(current) + len(sentence) > MAX_CHUNK_CHARS and current:
                        if len(current.strip()) > 0:
                            chunks.append(current.strip())
                        current = ""
                    current += sentence + " "

        if current.strip():
            chunks.append(current.strip())

        # Safety: if any chunk is still too long, hard-split it
        final_chunks = []
        for chunk in chunks:
            if len(chunk) > MAX_CHUNK_CHARS * 2:
                # Hard split at word boundaries
                words = chunk.split()
                sub = ""
                for word in words:
                    if len(sub) + len(word) + 1 > MAX_CHUNK_CHARS and sub:
                        final_chunks.append(sub.strip())
                        sub = ""
                    sub += word + " "
                if sub.strip():
                    final_chunks.append(sub.strip())
            else:
                final_chunks.append(chunk)

        return final_chunks if final_chunks else [text]

    def _translate_chunk(self, text: str, tokenizer: MarianTokenizer, model: MarianMTModel) -> str:
        """Translate a single chunk of text."""
        inputs = tokenizer(text, return_tensors="pt", padding=True, truncation=True, max_length=512)
        inputs = {k: v.to(self._device) for k, v in inputs.items()}

        with torch.no_grad():
            translated = model.generate(**inputs, no_repeat_ngram_size=3)

        return tokenizer.batch_decode(translated, skip_special_tokens=True)[0]

    def translate(
        self, text: str, source_lang: str | None = None, target_lang: str = "en"
    ) -> str:
        """Translate text between any supported language pair.

        Args:
            text: The source text to translate.
            source_lang: ISO 639-1 code. Detected automatically if None.
            target_lang: ISO 639-1 code for the target language (default: 'en').

        Returns:
            Translated text, or the original if source == target.
        """
        if not text or not text.strip():
            return ""

        t0 = time.monotonic()

        if source_lang is None:
            source_lang = self.detect_language(text)

        if source_lang == target_lang:
            log.info("Source == target (%s, %d chars), skipping", source_lang, len(text))
            return text

        model_name = self._get_model_name(source_lang, target_lang)
        tokenizer, model = self._load_model(model_name)

        chunks = self._split_chunks(text)
        translated_chunks = []

        for i, chunk in enumerate(chunks):
            if not chunk.strip():
                translated_chunks.append(chunk)
                continue
            translated = self._translate_chunk(chunk, tokenizer, model)
            translated_chunks.append(translated)

        result = " ".join(translated_chunks)

        elapsed = time.monotonic() - t0
        log.info(
            "Translated %d chars (%d chunks, lang=%s, model=%s) in %.1fs",
            len(text),
            len(chunks),
            source_lang,
            model_name.split("/")[-1],
            elapsed,
        )

        return result
