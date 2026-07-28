"""Shared fixtures for scraper-barch tests."""

import json

import pytest

from scraper_barch.config import Config, set_config


@pytest.fixture(autouse=True)
def _test_config():
    """Set up a test config that does not require real env vars."""
    cfg = Config()
    cfg.backend_url = "http://test-backend:8000"
    cfg.throttle = 0.0
    cfg.max_retries = 1
    cfg.user_agent = "test-agent/1.0"
    set_config(cfg)
    yield
    set_config(None)


# -- Sample viewer-page data ------------------------------------------------

UUID = "41827302-af11-4732-9a76-0c434720aeb6"
SIGNATURE = "R 43-II/1326"
PATH = "41/82/41827302-af11-4732-9a76-0c434720aeb6"


def _make_data(n_files=3, volume="Bd. 8", include_ve_list=True):
    files = [
        {
            "filename": f"R_43_II_1326_{i:04d}.jpg",
            "thumbnail": f"R_43_II_1326_{i:04d}_thumb.jpg",
            "width": 3600,
            "height": 4900,
        }
        for i in range(1, n_files + 1)
    ]
    data = {
        "title": SIGNATURE,
        "uuid": UUID,
        "path": PATH,
        "items": [{"files": files}],
        "frontend": {
            "ve_list": [{"titel": volume}] if (include_ve_list and volume) else []
        },
    }
    return data


def _make_viewer_html(data):
    # Mirrors the real page's embedded JS literal exactly as documented:
    # `var data = {...};\n` — trimmed down but structurally identical.
    blob = json.dumps(data, ensure_ascii=False)
    return f"""<!doctype html>
<html>
<head><title>Invenio Viewer</title></head>
<body>
<script>
var data = {blob};
var otherThing = 1;
</script>
</body>
</html>
"""


@pytest.fixture
def sample_data():
    return _make_data()


@pytest.fixture
def sample_viewer_html(sample_data):
    return _make_viewer_html(sample_data)


@pytest.fixture
def sample_data_no_volume():
    return _make_data(volume=None, include_ve_list=False)


@pytest.fixture
def sample_viewer_html_no_volume(sample_data_no_volume):
    return _make_viewer_html(sample_data_no_volume)


@pytest.fixture
def make_data():
    return _make_data


@pytest.fixture
def make_viewer_html():
    return _make_viewer_html
