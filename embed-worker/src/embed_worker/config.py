"""Configuration for embed worker."""

from worker_common.config import BaseConfig
import os


class Config(BaseConfig):
    def __init__(self):
        super().__init__()
        self.openai_api_key = os.environ.get("OPENAI_API_KEY", "")
        if not self.openai_api_key:
            raise ValueError("OPENAI_API_KEY environment variable is required")
