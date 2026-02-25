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
        device = "gpu:0" if use_gpu else "cpu"
        log.info("Initializing PaddleOCR engine (lang=%s, device=%s)...", lang, device)
        _engine = PaddleOCR(
            use_textline_orientation=True,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
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

    # Resize very large images to avoid PaddlePaddle GPU memory crashes
    max_edge = 2500
    w, h = img.size
    if max(w, h) > max_edge:
        scale = max_edge / max(w, h)
        new_w, new_h = int(w * scale), int(h * scale)
        log.info("Resizing %dx%d -> %dx%d for OCR", w, h, new_w, new_h)
        img = img.resize((new_w, new_h), Image.LANCZOS)

    img_array = np.array(img)

    result = engine.predict(img_array)

    if not result:
        return {"text": "", "confidence": 0.0, "regions": 0}

    res = result[0]
    texts = res["rec_texts"]
    scores = res["rec_scores"]

    if not texts:
        return {"text": "", "confidence": 0.0, "regions": 0}

    full_text = "\n".join(texts)
    avg_confidence = float(np.mean(scores)) if len(scores) > 0 else 0.0

    return {
        "text": full_text,
        "confidence": avg_confidence,
        "regions": len(texts),
    }
