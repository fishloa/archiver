"""OCR worker configuration."""

import os

from worker_common import BaseConfig

# ISO 639-1 -> PaddleOCR language name mapping
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


class Config(BaseConfig):
    ocr_lang: str

    def __init__(self):
        super().__init__()
        raw_lang = os.environ.get("OCR_LANG", "de")
        if raw_lang in ISO_TO_PADDLE:
            self.ocr_lang = ISO_TO_PADDLE[raw_lang]
        else:
            self.ocr_lang = raw_lang
