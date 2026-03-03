"""MLX Qwen-VL OCR engine — runs on Apple Silicon via Metal."""

import io
import logging

from PIL import Image

log = logging.getLogger(__name__)

_model = None
_processor = None

PROMPT = (
    "OCR this document page. Extract all text exactly as written, "
    "preserving the original language. Output only the raw text, no commentary."
)


def _load_model(model_name: str):
    global _model, _processor
    if _model is not None:
        return

    log.info("Loading model %s (this may take a minute on first run)...", model_name)
    from mlx_vlm import load

    _model, _processor = load(model_name)
    log.info("Model loaded successfully")


def process_image(image_bytes: bytes, model_name: str) -> dict:
    """Run VLM OCR on a page image. Returns {"text": str, "confidence": float}."""
    _load_model(model_name)

    from mlx_vlm import generate

    image = Image.open(io.BytesIO(image_bytes))

    # Resize if very large to stay within model limits
    max_dim = 2048
    if max(image.size) > max_dim:
        ratio = max_dim / max(image.size)
        new_size = (int(image.width * ratio), int(image.height * ratio))
        image = image.resize(new_size, Image.LANCZOS)

    text = generate(
        _model,
        _processor,
        image,
        PROMPT,
        max_tokens=4096,
        verbose=False,
    )

    return {
        "text": text.strip(),
        "confidence": 0.95,
    }
