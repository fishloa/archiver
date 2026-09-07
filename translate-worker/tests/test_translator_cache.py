"""Model cache eviction.

torch and transformers are GPU-tier dependencies that are not installed in the
test environment, so they are stubbed before importing the translator. The stubs
only need to satisfy the import and the handful of attributes the cache touches.
"""

import sys
import types
from collections import OrderedDict

import pytest


@pytest.fixture
def translator_module(monkeypatch):
    empty_cache_calls = []

    torch_stub = types.ModuleType("torch")
    torch_stub.cuda = types.SimpleNamespace(
        is_available=lambda: False,
        empty_cache=lambda: empty_cache_calls.append(1),
    )

    langdetect_stub = types.ModuleType("langdetect")
    langdetect_stub.detect = lambda text: "de"
    langdetect_stub.LangDetectException = type("LangDetectException", (Exception,), {})

    transformers_stub = types.ModuleType("transformers")
    transformers_stub.MarianMTModel = type("MarianMTModel", (), {})
    transformers_stub.MarianTokenizer = type("MarianTokenizer", (), {})

    monkeypatch.setitem(sys.modules, "torch", torch_stub)
    monkeypatch.setitem(sys.modules, "langdetect", langdetect_stub)
    monkeypatch.setitem(sys.modules, "transformers", transformers_stub)
    sys.modules.pop("translate_worker.translator", None)

    from translate_worker import translator

    return translator


def _seed(translator_module, names):
    """Build a Translator whose cache already holds the named models."""
    t = translator_module.Translator.__new__(translator_module.Translator)
    t._models = OrderedDict((n, object()) for n in names)
    t._tokenizers = {n: object() for n in names}
    t._device = "cpu"
    return t


def test_keeps_cache_within_the_resident_limit(translator_module):
    limit = translator_module.MAX_RESIDENT_MODELS
    names = [f"model-{i}" for i in range(limit + 2)]
    t = _seed(translator_module, names)

    t._evict_if_needed()

    assert len(t._models) == limit
    # The oldest two are gone, the most recent survive.
    assert list(t._models) == names[2:]


def test_evicting_a_model_also_drops_its_tokenizer(translator_module):
    limit = translator_module.MAX_RESIDENT_MODELS
    names = [f"model-{i}" for i in range(limit + 1)]
    t = _seed(translator_module, names)

    t._evict_if_needed()

    assert "model-0" not in t._tokenizers
    assert set(t._tokenizers) == set(t._models)


def test_no_eviction_when_under_the_limit(translator_module):
    names = [f"model-{i}" for i in range(translator_module.MAX_RESIDENT_MODELS)]
    t = _seed(translator_module, names)

    t._evict_if_needed()

    assert list(t._models) == names
