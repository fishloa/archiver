"""The client subclass must forward base-client options.

translate-worker crash-looped in production when `model` and `provider` were added to
worker_common.ProcessorClient: the subclasses overrode __init__ with a fixed signature, so
the new keyword arguments raised TypeError at startup rather than being passed through.
EmbedClient had the identical defect; this guards the pattern.
"""

from embed_worker.client import EmbedClient


def test_accepts_and_forwards_model_and_provider():
    client = EmbedClient(
        "http://backend:8080",
        "token",
        model="Qwen/Qwen3-Embedding-8B",
        provider="https://api.deepinfra.com/v1/openai",
    )
    try:
        assert client._headers["X-Worker-Model"] == "Qwen/Qwen3-Embedding-8B"
        assert client._headers["X-Worker-Provider"] == "https://api.deepinfra.com/v1/openai"
        assert client._headers["User-Agent"] == "embed-worker/0.1"
    finally:
        client.close()


def test_still_constructs_without_model():
    client = EmbedClient("http://backend:8080", "token")
    try:
        assert "X-Worker-Model" not in client._headers
    finally:
        client.close()
