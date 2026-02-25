"""Configuration from environment variables."""

import os

# ISO 639-1 → PaddleOCR language name mapping
ISO_TO_PADDLE = {
    "de": "german",
    "cs": "czech",
    "en": "en",
    "fr": "french",
    "pl": "polish",
    "ru": "ru",
    "it": "italian",
    "es": "es",
    "pt": "pt",
    "nl": "dutch",
    "la": "latin",
}


def to_paddle_lang(iso_code: str) -> str:
    """Convert ISO 639-1 code to PaddleOCR language name.

    Raises ValueError if the code is not supported.
    """
    lang = ISO_TO_PADDLE.get(iso_code.lower())
    if lang is None:
        raise ValueError(
            f"Unsupported language code '{iso_code}'. "
            f"Supported: {', '.join(sorted(ISO_TO_PADDLE.keys()))}"
        )
    return lang


class Config:
    backend_url: str
    processor_token: str
    ocr_lang: str
    poll_interval: int

    def __init__(self):
        self.backend_url = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
        self.processor_token = os.environ["PROCESSOR_TOKEN"]
        raw_lang = os.environ.get("OCR_LANG", "de")
        # Accept either ISO code or PaddleOCR name
        if raw_lang in ISO_TO_PADDLE:
            self.ocr_lang = ISO_TO_PADDLE[raw_lang]
        else:
            self.ocr_lang = raw_lang
        self.poll_interval = int(os.environ.get("POLL_INTERVAL", "10"))
