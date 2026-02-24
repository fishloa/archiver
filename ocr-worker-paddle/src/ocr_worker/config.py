"""Configuration from environment variables."""

import os


class Config:
    backend_url: str
    processor_token: str
    db_notify_url: str | None
    ocr_lang: str
    poll_interval: int

    def __init__(self):
        self.backend_url = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
        self.processor_token = os.environ["PROCESSOR_TOKEN"]
        self.db_notify_url = os.environ.get("DB_NOTIFY_URL")
        self.ocr_lang = os.environ.get("OCR_LANG", "german")
        self.poll_interval = int(os.environ.get("POLL_INTERVAL", "10"))
