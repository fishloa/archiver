"""Translate worker configuration."""

import os

from worker_common import BaseConfig


class Config(BaseConfig):
    def __init__(self):
        super().__init__()
        self.translate_base_url = os.environ.get(
            "TRANSLATE_BASE_URL", "https://api.deepinfra.com/v1/openai"
        )
        self.translate_api_key = os.environ.get("TRANSLATE_API_KEY", "")
        self.translate_model = os.environ.get("TRANSLATE_MODEL", "google/gemma-4-31B-it")
