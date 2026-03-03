"""Qwen VLM OCR worker configuration."""

import os

from worker_common import BaseConfig


class Config(BaseConfig):
    model_name: str
    ocr_lang: str
    ollama_url: str

    def __init__(self):
        super().__init__()
        self.model_name = os.environ.get("MODEL_NAME", "qwen2.5vl:7b")
        self.ocr_lang = os.environ.get("OCR_LANG", "de")
        self.ollama_url = os.environ.get("OLLAMA_URL", "http://localhost:11434")
