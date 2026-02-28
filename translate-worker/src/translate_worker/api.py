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
    return {"pairs": [{"source": "de", "target": "en"}, {"source": "cs", "target": "en"}]}


@app.post("/translate")
def translate(req: TranslateRequest) -> TranslateResponse:
    if _translator is None:
        raise HTTPException(status_code=503, detail="Translator not initialized")

    if req.target_lang != "en":
        raise HTTPException(
            status_code=400, detail=f"Unsupported target language: {req.target_lang}"
        )

    if req.source_lang not in ("de", "cs"):
        raise HTTPException(
            status_code=400, detail=f"Unsupported source language: {req.source_lang}"
        )

    if not req.text or not req.text.strip():
        return TranslateResponse(
            translated_text="", source_lang=req.source_lang, target_lang=req.target_lang
        )

    translated = _translator.translate(req.text, source_lang=req.source_lang)

    return TranslateResponse(
        translated_text=translated,
        source_lang=req.source_lang,
        target_lang=req.target_lang,
    )
