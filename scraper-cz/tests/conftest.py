"""Shared fixtures for scraper-cz tests."""

import pytest

from scraper_cz.config import Config, set_config


@pytest.fixture(autouse=True)
def _test_config():
    """Set up a test config that does not require real env vars."""
    cfg = Config()
    cfg.backend_url = "http://test-backend:8000"
    cfg.delay = 0.0
    cfg.max_retries = 1
    cfg.user_agent = "test-agent/1.0"
    set_config(cfg)
    yield
    set_config(None)


# -- Sample HTML fragments ------------------------------------------------

SAMPLE_SEARCH_RESULTS_HTML = """
<html>
<body>
<div>Celkem : 3</div>
<form action="/vademecum/PaginatorResult.action">
  <input name="_sourcePage" value="sp_token_abc">
  <input name="__fp" value="fp_token_xyz">
  <input name="rowTxt" value="1">
</form>

<aricle class="contentRecordList first">
  <a href="/vademecum/permalink?xid=aaaa1111-bbbb-cccc-dddd-eeee00001111">
    <div class="title contentRecordTitle" tooltip="Fond Alpha &bull; inv. č. 100, sig. 109-5/1, Korespondence">
      <span>Fond Alpha</span>
    </div>
  </a>
  <div class="cNAD"
  <span>1799</span></div>
  <div class="years"
  <span tooltip="1939-1942">1939-1942</span></div>
  <div class="descr"
  <span tooltip='Heydrich-Bormann correspondence, situation reports'>Heydrich-Bormann</span></div>
  <div><a tooltip="Zobrazit v plné kvalitě 15 skenů">15</a></div>
</aricle>

<aricle class="contentRecordList">
  <a href="/vademecum/permalink?xid=aaaa2222-bbbb-cccc-dddd-eeee00002222">
    <div class="title contentRecordTitle" tooltip="Fond Beta &bull; inv. č. 200, sig. 109-4/2, Správní záležitosti">
      <span>Fond Beta</span>
    </div>
  </a>
  <div class="cNAD"
  <span>1799</span></div>
  <div class="years"
  <span tooltip="1940-1943">1940-1943</span></div>
  <div class="descr"
  <span tooltip='Administrative matters, forced management'>Administrative</span></div>
  <div><a tooltip="Zobrazit v plné kvalitě 8 skenů">8</a></div>
</aricle>

<aricle class="contentRecordList">
  <a href="/vademecum/permalink?xid=aaaa3333-bbbb-cccc-dddd-eeee00003333">
    <div class="title contentRecordTitle" tooltip="Fond Gamma &bull; inv. č. 300, sig. 109-14/3">
      <span>Fond Gamma</span>
    </div>
  </a>
  <div class="cNAD"
  <span>1800</span></div>
  <div class="years"
  <span tooltip="1941">1941</span></div>
  <div class="descr"
  <span tooltip='Reports and analyses'>Reports</span></div>
</aricle>
</body>
</html>
"""


SAMPLE_RECORD_DETAIL_HTML = """
<html>
<body>
<div class="contentLine">
  Název fondu : Úřad říšského protektora Číslo pomůcky 1234
</div>
<div class="detailRecord">
  <span class="tabularLabel">Inv./přír. číslo:</span>
  <span class="tabularValue">1777</span>
  <span class="tabularLabel">Signatura:</span>
  <span class="tabularValue">109-5/5</span>
  <span class="tabularLabel">Datace:</span>
  <span class="tabularValue">1942</span>
  <span class="tabularLabel">Obsah:</span>
  <span class="tabularValue">Situační zpráva pro Bormanna</span>
  <span class="tabularLabel">Název fondu:</span>
  <span class="tabularValue">Úřad říšského protektora</span>
  <span class="tabularLabel">Číslo listu NAD:</span>
  <span class="tabularValue">1799</span>
</div>
<div>5 skenů</div>
<a href="Zoomify.action?entityRef=cz%2Farchives%2FCZ-100000010%2Finventare%2Fdao%2F1234">View scans</a>
</body>
</html>
"""


SAMPLE_ZOOMIFY_PAGE_HTML = """
<html>
<body>
<img src="/mrimage/vademecum/image//cz/archives/CZ-100000010/inventare/dao/images/42/aabbccdd-1122-3344-5566-778899001122.jpg/nahled_maly.jpg">
<img src="/mrimage/vademecum/image//cz/archives/CZ-100000010/inventare/dao/images/42/aabbccdd-1122-3344-5566-778899002233.jpg/nahled_maly.jpg">
<img src="/mrimage/vademecum/image//cz/archives/CZ-100000010/inventare/dao/images/43/ccddaabb-1122-3344-5566-778899003344.jpg/nahled_maly.jpg">
<a href="PaginatorMedia.action?_sourcePage=sp_media_token&amp;row=10">Next</a>
</body>
</html>
"""


@pytest.fixture
def search_html():
    return SAMPLE_SEARCH_RESULTS_HTML


@pytest.fixture
def record_html():
    return SAMPLE_RECORD_DETAIL_HTML


@pytest.fixture
def zoomify_html():
    return SAMPLE_ZOOMIFY_PAGE_HTML
