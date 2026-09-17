"""Shared fixtures for scraper-ebadatelna tests."""

import pytest

from scraper_ebadatelna.config import Config, set_config


@pytest.fixture(autouse=True)
def _test_config():
    cfg = Config()
    cfg.backend_url = "http://test-backend:8000"
    cfg.delay = 0.0
    cfg.max_retries = 1
    cfg.user_agent = "test-agent/1.0"
    cfg.ebadatelna_email = "researcher@example.com"
    cfg.ebadatelna_password = "s3cret"
    set_config(cfg)
    yield
    set_config(None)


# The homepage carries the anti-forgery token the login header needs.
HOMEPAGE_HTML = """
<html><body>
<input name="__RequestVerificationToken" type="hidden" value="tok-abc-123" />
</body></html>
"""

# GetNodeInfo: bold description, <br/> line breaks, then the signature.
NODE_INFO_HTML = """
<b>Svazek vyšetřovací dokumentace k osobě Ing. František Dvořák<br/>\
Součástí svazku jsou důkazní dokumentace o úmrtí Humprechta Czernina z Chudenic</b>
<br />
    signatura: 325-113-1
"""

# OcrFolderResult: one block per matching scan.
FOLDER_RESULT_HTML = """
<div class"eb-ocr-folder-block">
<div style="position: relative" class="eb-ocr-region-block">
<a href="#" onclick=" eb.view.scan.onClickShowScan(event, 'f626290', 28, 1);" >
<img src="/Scan/GetImageSnippet?path=TOKEN1&amp;w=1000&amp;h=1413&amp;ulx=0&amp;uly=390&amp;lrx=1000&amp;lry=491" />
<div class="eb-ocr-highlight-block" style="left:233px;top:34px; width:122px; height: 23px;"></div>
<span class="eb-ocr-page-num">Sken číslo: 29 </span></a></div>
<div style="position: relative" class="eb-ocr-region-block">
<a href="#" onclick=" eb.view.scan.onClickShowScan(event, 'f626290', 43, 1);" >
<img src="/Scan/GetImageSnippet?path=TOKEN2&amp;w=1000&amp;h=1413" />
<span class="eb-ocr-page-num">Sken číslo: 44 </span></a></div>
</div>
"""
