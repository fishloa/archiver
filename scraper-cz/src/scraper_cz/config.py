"""Configuration from environment variables."""

import os


class Config:
    """Scraper configuration loaded from environment variables."""

    def __init__(self):
        self.backend_url: str = os.environ.get("BACKEND_URL", "")
        self.lang: str = os.environ.get("SCRAPER_LANG", "german")
        self.delay: float = float(os.environ.get("SCRAPER_DELAY", "0.5"))
        self.max_retries: int = int(os.environ.get("SCRAPER_MAX_RETRIES", "3"))
        self.user_agent: str = os.environ.get(
            "USER_AGENT",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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
