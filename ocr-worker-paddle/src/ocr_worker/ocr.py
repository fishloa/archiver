"""PaddleOCR wrapper (v3.x API)."""

import logging
from io import BytesIO

import numpy as np
from PIL import Image
from paddleocr import PaddleOCR

log = logging.getLogger(__name__)

_engine: PaddleOCR | None = None


def get_engine(lang: str = "german", use_gpu: bool = True) -> PaddleOCR:
    """Get or create the PaddleOCR engine (singleton)."""
    global _engine
    if _engine is None:
        device = "gpu" if use_gpu else "cpu"
        log.info("Initializing PaddleOCR engine (lang=%s, device=%s)...", lang, device)
        _engine = PaddleOCR(
            use_textline_orientation=True,
            lang=lang,
            device=device,
        )
        log.info("PaddleOCR engine ready")
    return _engine


def process_image(image_bytes: bytes, lang: str = "german", use_gpu: bool = True) -> dict:
    """Run OCR on a JPEG image.

    Returns:
        {
            "text": str,        # All extracted text joined by newlines
            "confidence": float, # Average confidence across all detections
            "regions": int,     # Number of text regions detected
        }
    """
    engine = get_engine(lang, use_gpu)

    img = Image.open(BytesIO(image_bytes))
    if img.mode != "RGB":
        img = img.convert("RGB")
    img_array = np.array(img)

    result = engine.ocr(img_array, cls=True)

    if not result or not result[0]:
        return {"text": "", "confidence": 0.0, "regions": 0}

    lines = []
    confidences = []

    for line in result[0]:
        box, (text, conf) = line
        lines.append(text)
        confidences.append(conf)

    full_text = "\n".join(lines)
    avg_confidence = sum(confidences) / len(confidences) if confidences else 0.0

    return {
        "text": full_text,
        "confidence": avg_confidence,
        "regions": len(lines),
    }
