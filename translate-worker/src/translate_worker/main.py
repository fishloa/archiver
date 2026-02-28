"""Translate worker main loop.

Claims translate_page and translate_record jobs from the backend,
translates text from German/Czech to English using Helsinki-NLP
MarianMT models, and posts the results back.

Subscribes to the backend's SSE job events stream for real-time
notifications. Falls back to polling on SSE disconnect.
"""

import json
import logging

from worker_common import run_sse_loop, wait_for_backend

log = logging.getLogger(__name__)

JOB_KINDS = ("translate_page", "translate_record")


def _job_lang(job: dict) -> str | None:
    """Extract language ISO code from job payload, if present."""
    payload = job.get("payload")
    if payload:
        try:
            data = json.loads(payload) if isinstance(payload, str) else payload
            return data.get("lang")
        except (json.JSONDecodeError, AttributeError):
            pass
    return None


def process_translate_page(client, translator, job: dict) -> None:
    """Process a single translate_page job."""
    job_id = job["id"]
    page_id = job["pageId"]
    record_id = job.get("recordId", "?")
    source_lang = _job_lang(job)

    log.info(
        "Processing translate_page job %d: page %d (record %s, lang=%s)",
        job_id, page_id, record_id, source_lang or "auto",
    )

    page_data = client.get_page_text(page_id)
    text = page_data.get("text") or ""

    if not text.strip():
        log.info("  Page %d has no text, skipping translation", page_id)
        client.complete_job(job_id)
        return

    translated = translator.translate(text, source_lang=source_lang)

    client.submit_page_translation(page_id, translated)
    client.complete_job(job_id)
    log.info("  Job %d completed: %d chars -> %d chars", job_id, len(text), len(translated))


def process_translate_record(client, translator, job: dict) -> None:
    """Process a single translate_record job."""
    job_id = job["id"]
    record_id = job.get("recordId")
    metadata_lang = _job_lang(job)

    if record_id is None:
        raise ValueError(f"Job {job_id} has no recordId")

    log.info(
        "Processing translate_record job %d: record %d (metadata_lang=%s)",
        job_id, record_id, metadata_lang or "auto",
    )

    record = client.get_record(record_id)
    title = record.get("title") or ""
    description = record.get("description") or ""

    if not title.strip() and not description.strip():
        log.info("  Record %d has no title/description, skipping translation", record_id)
        client.complete_job(job_id)
        return

    title_en = translator.translate(title, source_lang=metadata_lang) if title.strip() else ""
    description_en = translator.translate(description, source_lang=metadata_lang) if description.strip() else ""

    client.submit_record_translation(record_id, title_en, description_en)
    client.complete_job(job_id)
    log.info(
        "  Job %d completed: title %d->%d chars, desc %d->%d chars",
        job_id,
        len(title),
        len(title_en),
        len(description),
        len(description_en),
    )


def process_job(client, translator, job: dict) -> None:
    """Route a job to the appropriate handler."""
    kind = job.get("kind", "")
    if kind == "translate_page":
        process_translate_page(client, translator, job)
    elif kind == "translate_record":
        process_translate_record(client, translator, job)
    else:
        raise ValueError(f"Unknown job kind: {kind}")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    from .config import Config
    from .client import ProcessorClient
    from .translator import Translator
    from .api import app as api_app, set_translator

    import threading
    import uvicorn

    cfg = Config()
    client = ProcessorClient(cfg.backend_url, cfg.processor_token)

    log.info("Loading translation models...")
    translator = Translator()

    # Start FastAPI server in daemon thread for on-demand translation
    set_translator(translator)
    api_thread = threading.Thread(
        target=uvicorn.run,
        args=(api_app,),
        kwargs={"host": "0.0.0.0", "port": 8001, "log_level": "info"},
        daemon=True,
    )
    api_thread.start()
    log.info("Translation API server started on port 8001")

    log.info(
        "Translate worker starting (backend=%s, poll=%ds)",
        cfg.backend_url,
        cfg.poll_interval,
    )

    wait_for_backend(client, JOB_KINDS[0])

    try:
        run_sse_loop(
            client,
            job_kinds=JOB_KINDS,
            process_fn=lambda job: process_job(client, translator, job),
            poll_interval=cfg.poll_interval,
        )
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
