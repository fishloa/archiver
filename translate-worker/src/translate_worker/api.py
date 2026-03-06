"""FastAPI HTTP server for on-demand translation."""

import logging
from pydantic import BaseModel
from fastapi import FastAPI, HTTPException

log = logging.getLogger(__name__)

app = FastAPI(title="Translate Worker")

# Set by main.py before server starts
_translator = None


def set_translator(translator):
    global _translator
    _translator = translator


class TranslateRequest(BaseModel):
    text: str
    source_lang: str
    target_lang: str = "en"


class TranslateResponse(BaseModel):
    translated_text: str
    source_lang: str
    target_lang: str


@app.get("/capabilities")
def capabilities():
    if _translator is None:
        return {"pairs": []}
    pairs = [{"source": s, "target": t} for s, t in _translator.available_pairs()]
    return {"pairs": pairs}


@app.post("/translate")
def translate(req: TranslateRequest) -> TranslateResponse:
    if _translator is None:
        raise HTTPException(status_code=503, detail="Translator not initialized")

    if not req.text or not req.text.strip():
        return TranslateResponse(
            translated_text="", source_lang=req.source_lang, target_lang=req.target_lang
        )

    try:
        translated = _translator.translate(
            req.text, source_lang=req.source_lang, target_lang=req.target_lang
        )
    except OSError as e:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported pair: {req.source_lang}→{req.target_lang}",
        ) from e

    return TranslateResponse(
        translated_text=translated,
        source_lang=req.source_lang,
        target_lang=req.target_lang,
    )
