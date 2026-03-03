"""Qwen VLM OCR worker configuration."""

import os

from worker_common import BaseConfig


class Config(BaseConfig):
    model_name: str
    ocr_lang: str

    def __init__(self):
        super().__init__()
        self.model_name = os.environ.get("MODEL_NAME", "Qwen/Qwen2.5-VL-7B-Instruct")
        self.ocr_lang = os.environ.get("OCR_LANG", "de")
