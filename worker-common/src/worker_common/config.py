"""Base configuration from environment variables."""

import os


class BaseConfig:
    backend_url: str
    processor_token: str
    poll_interval: int

    def __init__(self):
        self.backend_url = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
        self.processor_token = os.environ["PROCESSOR_TOKEN"]
        self.poll_interval = int(os.environ.get("POLL_INTERVAL", "10"))
