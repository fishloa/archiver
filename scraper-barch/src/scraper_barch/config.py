"""Configuration from environment variables."""

import os


class Config:
    """Scraper configuration loaded from environment variables."""

    def __init__(self):
        self.backend_url: str = os.environ.get("BACKEND_URL", "")
        self.processor_token: str = os.environ.get("PROCESSOR_TOKEN", "")
        self.lang: str = os.environ.get("SCRAPER_LANG", "de")
        self.metadata_lang: str = os.environ.get("SCRAPER_METADATA_LANG", "de")
        # Delay between image downloads. Invenio has no auth/session requirements,
        # but we still throttle to be a considerate client.
        self.throttle: float = float(os.environ.get("SCRAPER_THROTTLE", "1.0"))
        self.max_retries: int = int(os.environ.get("SCRAPER_MAX_RETRIES", "3"))
        self.user_agent: str = os.environ.get(
            "USER_AGENT",
            "archiver-scraper-barch/0.1 (digital archive ingestion bot; "
            "+https://archive.czernin.eu)",
        )

    def require_backend(self) -> str:
        """Return backend URL or raise if not configured."""
        if not self.backend_url:
            raise RuntimeError(
                "BACKEND_URL environment variable is required. "
                "Set it to the archiver backend API base URL."
            )
        return self.backend_url


# Module-level singleton, lazily overridable
_config: Config | None = None


def get_config() -> Config:
    global _config
    if _config is None:
        _config = Config()
    return _config


def set_config(cfg: Config | None) -> None:
    global _config
    _config = cfg
