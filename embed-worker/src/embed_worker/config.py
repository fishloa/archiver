"""Configuration for embed worker."""

from worker_common.config import BaseConfig
import os


class Config(BaseConfig):
    def __init__(self):
        super().__init__()
        self.tei_url = os.environ.get("EMBED_TEI_URL", "")
        self.tei_key = os.environ.get("EMBED_TEI_KEY", "")
        if not self.tei_url:
            raise ValueError("EMBED_TEI_URL environment variable is required")
