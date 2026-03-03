"""Qwen-VL OCR engine via Ollama API."""

import base64
import logging

import httpx

log = logging.getLogger(__name__)

PROMPT = (
    "OCR this document page. Extract all text exactly as written, "
    "preserving the original language. Output only the raw text, no commentary."
)


def process_image(image_bytes: bytes, model_name: str, ollama_url: str) -> dict:
    """Run VLM OCR on a page image via Ollama. Returns {"text": str, "confidence": float}."""
    b64 = base64.b64encode(image_bytes).decode("ascii")

    resp = httpx.post(
        f"{ollama_url}/api/generate",
        json={
            "model": model_name,
            "prompt": PROMPT,
            "images": [b64],
            "stream": False,
        },
        timeout=300.0,
    )
    resp.raise_for_status()
    data = resp.json()

    return {
        "text": data["response"].strip(),
        "confidence": 0.95,
    }
