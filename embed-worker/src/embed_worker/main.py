"""Embed worker main loop.

Claims embed_record jobs, embeds document text via TEI (BGE-M3),
and stores the vectors in the backend for semantic search.
"""

import logging

import httpx
from worker_common import run_sse_loop, wait_for_backend

from .chunker import chunk_text

log = logging.getLogger(__name__)

JOB_KIND = "embed_record"
BATCH_SIZE = 16


def embed_batch(tei_url: str, tei_key: str, texts: list[str]) -> list[list[float]]:
    """Embed a batch of texts via TEI server.

    Retries with halved batch size on 422 (token limit exceeded).
    Falls back to one-at-a-time as last resort.
    """
    headers = {"Content-Type": "application/json"}
    if tei_key:
        headers["Authorization"] = f"Bearer {tei_key}"

    try:
        resp = httpx.post(
            f"{tei_url}/embed",
            json={"inputs": texts},
            headers=headers,
            timeout=120.0,
        )
        resp.raise_for_status()
        return resp.json()
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 422 and len(texts) > 1:
            log.warning("TEI 422 with batch size %d, splitting in half", len(texts))
            mid = len(texts) // 2
            left = embed_batch(tei_url, tei_key, texts[:mid])
            right = embed_batch(tei_url, tei_key, texts[mid:])
            return left + right
        raise


def process_one(client, tei_url: str, tei_key: str, job: dict) -> None:
    """Process a single embed_record job."""
    job_id = job["id"]
    record_id = job["recordId"]

    log.info("Processing job %d: embedding record %d", job_id, record_id)

    # 1. Fetch record metadata
    meta = client.get_record_metadata(record_id)
    title = meta.get("title_en") or meta.get("title") or ""
    ref_code = meta.get("reference_code") or ""

    # 2. Fetch all pages with OCR text
    pages = client.get_record_pages(record_id)

    # 3. Build text content to embed
    all_chunks = []

    # Metadata chunk — prefer English translations for bilingual coverage
    meta_text = ""
    if meta.get("title_en"):
        meta_text += f"Title: {meta['title_en']}\n"
    elif title:
        meta_text += f"Title: {title}\n"
    if ref_code:
        meta_text += f"Reference: {ref_code}\n"
    if meta.get("description_en"):
        meta_text += f"Description: {meta['description_en']}\n"
    if meta_text.strip():
        for i, chunk in enumerate(chunk_text(meta_text)):
            all_chunks.append({"page_id": None, "chunk_index": i, "content": chunk})

    # Page text chunks — prefer original OCR text (text_raw) for multilingual embedding.
    # BGE-M3 handles Czech/German/English natively, so we embed the original text
    # rather than waiting for English translations.
    skipped = 0
    for page in pages:
        page_id = page.get("page_id")
        text = page.get("text_raw") or page.get("text_en") or ""
        if not text.strip():
            skipped += 1
            continue

        page_chunks = chunk_text(text)
        for i, chunk in enumerate(page_chunks):
            all_chunks.append({"page_id": page_id, "chunk_index": i, "content": chunk})

    if skipped:
        log.info("  Skipped %d pages without OCR text", skipped)

    if not all_chunks:
        log.info("  No text to embed for record %d", record_id)
        client.complete_job(job_id)
        return

    log.info("  Chunked into %d pieces", len(all_chunks))

    # 4. Embed in batches via TEI
    for batch_start in range(0, len(all_chunks), BATCH_SIZE):
        batch = all_chunks[batch_start : batch_start + BATCH_SIZE]
        texts = [c["content"] for c in batch]

        embeddings = embed_batch(tei_url, tei_key, texts)

        for j, embedding in enumerate(embeddings):
            batch[j]["embedding"] = embedding

    # 5. Store via backend API
    chunks_payload = [
        {
            "pageId": c["page_id"],
            "chunkIndex": c["chunk_index"],
            "content": c["content"],
            "embedding": c["embedding"],
        }
        for c in all_chunks
    ]
    result = client.store_embeddings(record_id, chunks_payload)
    log.info(
        "  Stored %d chunks for record %d", result.get("chunksStored", 0), record_id
    )

    # 6. Complete the job
    client.complete_job(job_id)
    log.info("  Job %d completed", job_id)


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    from .config import Config
    from .client import EmbedClient

    cfg = Config()
    client = EmbedClient(cfg.backend_url, cfg.processor_token)

    log.info(
        "Embed worker starting (backend=%s, tei=%s, poll=%ds)",
        cfg.backend_url,
        cfg.tei_url,
        cfg.poll_interval,
    )

    wait_for_backend(client, JOB_KIND)

    try:
        run_sse_loop(
            client,
            job_kinds=[JOB_KIND],
            process_fn=lambda job: process_one(client, cfg.tei_url, cfg.tei_key, job),
            poll_interval=cfg.poll_interval,
        )
    except KeyboardInterrupt:
        pass
    finally:
        client.close()


if __name__ == "__main__":
    main()
