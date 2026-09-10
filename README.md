# Archiver

Digital archive for historical documents — scrapes, OCRs, translates, embeds and indexes
material from Czech, Austrian and German archives, and makes it searchable in English.

Live at [archive.czernin.eu](https://archive.czernin.eu). Currently holds **3,212 records
/ 128,484 pages**, predominantly German-language administrative record from the Protectorate
of Bohemia and Moravia, with Czech and bilingual material throughout.

## How it works

```
                        host nginx (:443, TLS)
                              ↓
scrapers ──→            web (nginx :8099, OAuth2)
                       ↙              ↘
          frontend (SvelteKit)    backend (Spring Boot)
                                       ↕↑
                                    PostgreSQL
                                       ↕↑               ←── embed-worker
                                 archiver_store
```

Scrapers pull records from archive websites and POST them to the backend. The backend owns
all state and runs a formal pipeline state machine; workers only ever talk to it over HTTP.
**Nothing but the backend touches PostgreSQL or the file store.**

```
ingesting → ocr_pending → ocr_done → pdf_pending → pdf_done → translating → embedding → matching → complete
```

## Engines

Every stage that used to run on a local GPU is now a hosted API call. The stack uses **no
GPU at all**.

| Stage | Engine | Notes |
|---|---|---|
| OCR | Mistral OCR (`mistral-ocr-latest`) | returns markdown; 16 workers, ~19 pages/s |
| Translation | `google/gemma-4-31B-it` | markdown in, markdown out |
| Embedding | `Qwen/Qwen3-Embedding-8B` @ 1024d | `halfvec`, cross-lingual |
| Searchable PDF | reportlab | invisible text layer over the page image |

Alternates remain in the image and are switchable by a single env var, both currently
disabled: Claude vision OCR (`CLAUDE_OCR_ENABLED`) and Qwen3-VL via Ollama on a Mac Studio
(`QWEN_OCR_ENABLED`). Mistral is the only OCR engine actually running.

Every one of these was chosen by benchmarking against this archive's own pages rather than
published leaderboards, because the failure that matters here is not fluency — it is a
model quietly dropping or inventing a name, a date or a figure in a document being used as
evidence. One candidate was rejected for replacing `Strachwitz` with two invented names in
a list of expropriated families; another for silently omitting the date a loyalty
declaration was signed.

## Searching

Queries are English, pages are German or Czech, so every retrieval is cross-lingual. The
original OCR text is embedded — not the translation — because the embedding model handles
the source languages natively and translating first would compound two lossy steps.

Chunks carry their markdown heading path, prefixed to the embedded text, so a chunk taken
from the middle of a section still knows its subject.

## Running it

```bash
make dev-backend      # backend on :8080
make dev-frontend     # frontend dev server
make test             # everything
make lint             # spotless, ruff, eslint
```

Backend tests use Testcontainers and need Docker running. Python worker tests need only
the worker's own venv.

## Deployment

Jenkins (`ci.icomb.place`) builds on push with path-based change detection, pushes images to
a Nexus registry, and fires a Portainer webhook. Stack 183 on zelkova.

Two things worth knowing before trusting a green build:

- **Change detection compares only the last commit** (`git diff HEAD~1 HEAD -- <dir>`),
  not the pushed range. Push three commits and only the final one's directories are
  rebuilt; everything in the earlier commits is silently skipped. A green build is not
  evidence that your change shipped — read the `backend=… pdf=… translate=…` line in the
  log, or use `BUILD_ALL=true`. This has bitten twice in one day.
- **A green build after a red one is the dangerous case.** The red build pushes no
  images, and the green one only rebuilds its own commit's directories, so the system
  ends up split across old and new images with nothing reporting a problem.
- **Removing a service from the compose file does not stop it.** Portainer needs
  `Prune: true`, otherwise the containers keep running. PaddleOCR ran for five weeks
  after being deleted from the compose file this way.

## Data notes

- `text_raw` is stored **verbatim** as the OCR engine produced it. Nothing is escaped or
  normalised on write.
- `page_text.content_type` says whether that text is markdown or plain. Consumers must read
  it — `- 5 -` as a centred page number is byte-identical to a markdown bullet, so the
  format cannot be inferred from the content.
- Applied Flyway migrations are **never** edited. Add a new one.
- 66,796 pages still carry text from the retired PaddleOCR engine — the weakest transcription
  in the archive, and the bulk of what a re-OCR pass would improve.

## Layout

| Path | |
|---|---|
| `backend/` | Spring Boot API, pipeline state machine, internal OCR workers |
| `frontend/` | SvelteKit UI |
| `worker-common/` | shared Python library — HTTP client, SSE loop, markdown handling |
| `embed-worker/` | pipeline worker |
| `scraper-*/` | one per source archive |
| `deploy/` | docker-compose for the Portainer stack |
| `docs/superpowers/plans/` | implementation plans |
