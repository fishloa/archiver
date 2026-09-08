# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

Digital archive management system — scrapes, OCRs, translates, embeds, and indexes historical documents.

```
                        host nginx (:443, TLS)
                              ↓
scrapers ──→            web (nginx :8099, OAuth2)
                       ↙              ↘
          frontend (SvelteKit)    backend (Spring Boot)
                                       ↕↑               ←── pdf-worker
                                    PostgreSQL           ←── translate-worker
                                       ↕↑               ←── embed-worker
                                 archiver_store
```

All workers communicate **only** via the backend HTTP API (`/api/processor/*`).
No service in the stack uses the GPU any more — OCR, translation and embedding are all
hosted APIs. The archiver stack holds ~0 MiB of the zelkova A2000.
They send/receive binaries and metadata over HTTP — no direct filesystem or DB access.
Only the backend touches PostgreSQL and archiver_store.

### Services

| Service | Stack | Description |
|---------|-------|-------------|
| backend | Java 25 / Spring Boot 4.1 | REST API, job orchestration, SSE events |
| frontend | SvelteKit + Tailwind v4 | UI with Verdant design system (`--vui-*` CSS vars) |
| worker-common | Python shared lib | Base `ProcessorClient`, SSE loop, job lifecycle helpers |
| pdf-worker | Python + reportlab | Builds searchable PDFs with invisible text overlay |
| translate-worker | Python | LLM translation via OpenAI-compatible API (gemma-4-31B), markdown-preserving |
| embed-worker | Python | Heading-aware chunking, embeds via Qwen3-Embedding-8B (1024-dim, halfvec) |
| entity-worker | Python | Named entity extraction (dormant — commented out in compose) |
| ocr-worker-qwen3vl | Python + Ollama | Qwen3-VL OCR via Ollama (not containerized, runs on Mac Studio) |
| web | nginx | Internal reverse proxy: OAuth2 routing, SSE buffering, backend/frontend dispatch |
| scraper-cz | Python | Czech National Archives (Zoomify tiles → PDF) |
| scraper-ebadatelna | Python | Czech Archive of Security Forces (auth required) |
| scraper-findbuch | Python | Austrian victims/property database (auth required) |
| scraper-oesta | Python | Austrian State Archives |
| scraper-matricula | Python | Matricula Online church records |
| scraper-arolsen | Python | Arolsen Archives (German Holocaust documentation) |
| scraper-ddb | Python | Deutsche Digitale Bibliothek (German Digital Library) |

**OCR engines.** Internal backend workers, selected by `OCR_DEFAULT_ENGINE`:
`ocr_page_mistral` (Mistral OCR API — current default, returns markdown),
`ocr_page_claude` (Claude vision) and `ocr_page_qwen3vl` (Ollama on the Mac Studio) are
both **disabled in the deploy** — they remain in the image and are re-enabled with one env
var each. Mistral is the only OCR engine running. PaddleOCR was retired in September 2026:
it ran no jobs after 2 August and the backend registered no worker for it. Its 66,796
pages of stored text remain in `page_text` until re-OCR'd.

### Document Pipeline

```
ingesting → ocr_pending → ocr_done → pdf_pending → pdf_done → translating → embedding → matching → complete
                                                              → entities_pending → entities_done (future)
```

Pipeline transitions are managed by `PipelineStateMachine` — a formal state machine with guards, actions, and validated transitions. `autoAdvance(recordId)` chains through all applicable transitions.

When all OCR jobs complete for a record, the state machine auto-enqueues:
- `build_searchable_pdf` (1 per record)
- `translate_record` (metadata translation, uses `record.metadata_lang`)
- `translate_page` (per page; the LLM handles any source language, no detection step)
- `embed_record` (heading-aware chunks of the ORIGINAL text — not the translation —
  embedded cross-lingually so English queries retrieve German and Czech pages)
- `match_persons` (heuristic + LLM person matching against family tree)

### Text Content Types

`page_text.content_type` records the media type of `text_raw` — `text/markdown` from
Mistral OCR, `text/plain` from everything else. **Consumers must read it, never sniff
it.** A typescript's centred page number `- 5 -` is byte-identical to a markdown bullet,
so the formats cannot be told apart by inspection, and guessing turns page numbers into
bullets across the archive.

`text_raw` is stored exactly as the engine produced it — nothing is escaped or
normalised on write. This is an archive backing a citizenship application; the stored
transcription must be what the OCR engine actually said.

All markdown handling lives in `worker_common.markdown`, shared by every Python worker:

- `to_plain_text(text, content_type)` — strips markup for the PDF's invisible text layer
- `parse_blocks` / `render_blocks` — separate a block's marker from its translatable text
- `iter_sections(text, content_type)` — `(heading path, body)` pairs for embedding context

`parse_blocks` distinguishes hard-wrapped prose (rejoin into sentences) from forms
(one field per line, never join) by line-width regularity. A wage card whose lines are
joined loses every label/value pairing.

### Embedding

Queries arrive in English; pages are German or Czech. Every retrieval is cross-lingual.

- Model `Qwen/Qwen3-Embedding-8B` at `dimensions: 1024`, requested server-side
- Matryoshka-trained, so truncating 4096 → 1024 measured **no** quality loss and stays
  under pgvector's 2,000-dimension HNSW ceiling
- Stored as `halfvec(1024)` — measured identical to fp32, half the bytes
- Chunks carry `heading`, prefixed to the embedded content so a mid-section chunk still
  carries its section's subject

**The trap:** two components embed text. `embed-worker` embeds passages with **no**
prefix; `SemanticSearchController.embedText()` embeds queries **with** Qwen3's
instruction prefix. Both read `archiver.embed.*`. If they drift apart retrieval degrades
silently — no error is raised anywhere. Only a search with a known-correct answer
catches it.

### Language Handling

- `record.lang` = content language (ISO 639-1, e.g. "de"), passed to OCR engine
- `record.metadata_lang` = catalog language (e.g. "cs"), used for title/description translation
- DB enforces 2-char ISO codes via CHECK constraints
- Scraper sets both independently; don't assume they're the same
- Translation skipped if lang = "en"

## Common Commands

### Build / Run / Test / Lint (via Makefile)

```bash
make dev-backend          # cd backend && ./gradlew bootRun
make dev-frontend         # cd frontend && bun run dev
make test-backend         # cd backend && ./gradlew test
make test-scraper         # cd scraper-cz && pytest -v
make test-pdf             # cd pdf-worker && pytest -v
make test-entity          # cd entity-worker && pytest -v
make test-frontend        # cd frontend && bun test
make test                 # all of the above
make lint                 # all linters (spotless, ruff, eslint+prettier)
make test-smoke           # web/test-endpoints.sh (quick endpoint smoke test)
make validate-deploy      # web/validate-deploy.sh (full proxy chain validation)
```

### Single test / targeted commands

```bash
# Backend — single test class
cd backend && ./gradlew test --tests '*IngestControllerTest'

# Backend — format code
cd backend && ./gradlew spotlessApply

# Python workers — single test file
cd pdf-worker && pytest tests/test_something.py -v

# Python — format + lint fix
ruff check --fix scraper-cz/ && ruff format scraper-cz/

# Frontend — type check
cd frontend && bun run check
```

### Backend tests

Tests use Testcontainers (PostgreSQL). Requires Docker running.
New tests should use Java `HttpClient` (not REST Assured — Groovy 5 compat issues with Spring Boot 4.0).
Config: `backend/src/test/resources/application-test.yml`.

## Backend Details

- **Package**: `place.icomb.archiver` — `controller/`, `service/`, `model/`, `dto/`, `repository/`, `config/`
- Spring Data JDBC (not JPA) — entities use `@Table`, no `@Entity`
- MapStruct for DTO mapping
- `BeanPropertyRowMapper` maps snake_case columns to camelCase Java fields
- Flyway migrations in `backend/src/main/resources/db/migration/V*.sql`. V1..V26 were squashed into `V1__baseline.sql` on 2026-09-08, generated from production's verified schema; the originals remain in git history. Existing databases are baselined at V1 rather than running it. **Never edit an applied migration** — add a new one.
- SpringDoc OpenAPI at `/swagger-ui.html`
- `--enable-preview` Java flag enabled for compilation and tests
- Spotless with Google Java Format for code formatting

### Controllers

| Controller | Path prefix | Purpose |
|-----------|-------------|---------|
| `IngestController` | `/api/ingest` | Record creation from scrapers |
| `ProcessorController` | `/api/processor` | Worker job API (claim, complete, fail, SSE) |
| `CatalogueController` | `/api/records` | Record CRUD, SSE events for frontend |
| `ViewerController` | `/api/viewer` | Frontend-specific API (search, pipeline stats) |
| `ApiController` | `/api/v1` | Machine-readable API for LLM tools |
| `SemanticSearchController` | `/api/semantic-search` | Vector similarity search |

### Key service files

- `service/JobService.java` — job orchestration, audit pipeline
- `service/PipelineStateMachine.java` — formal state machine for pipeline transitions (guards, actions, chaining)
- `service/PersonMatchWorker.java` — internal scheduled worker for `match_persons` jobs
- `service/IngestService.java` — record creation, OCR job enqueuing
- `service/StorageService.java` — file storage abstraction over archiver_store
- `service/PdfExportService.java` — PDF generation from page images

## Frontend Details

- SvelteKit with `@sveltejs/adapter-node`, Tailwind v4, `lucide-svelte` icons
- Backend proxy: all `/api/*` requests go to `BACKEND_URL` (env var)
- SSE via `EventSource('/api/records/events')` with debounced `invalidateAll()`
- API helpers in `frontend/src/lib/server/api.ts`
- Routes: `/` (search), `/records` (list), `/records/[id]` (detail), `/records/[id]/pages/[seq]` (page viewer), `/pipeline` (stats), `/admin`

## Worker Pattern

All Python workers depend on `worker-common` (installed as local package). Shared code:
- `ProcessorClient` — HTTP client with Bearer token auth, SSE connection
- `run_sse_loop()` — listens for `job_enqueued` events, claims and processes jobs
- `wait_for_backend()` — startup readiness check
- Workers declare which job kinds they handle

Worker env vars: `BACKEND_URL`, `PROCESSOR_TOKEN`. GPU workers also need `HOME=/tmp`.

## Deployment

- **CI/CD**: Jenkins (`ci.icomb.place`), Jenkinsfile with path-based change detection
- **Registry**: `dockerregistry.icomb.place` (Nexus)
- **Hosting**: Portainer stack #183 on zelkova (endpoint 2)
- **Redeploy**: `curl -X POST https://docker.icomb.place/api/stacks/webhooks/b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71`

### Waiting for a build

Pushing triggers a build via webhook — **never** run `jk run start` after a push, it
creates a duplicate. To wait for the build that the push started, block on it rather than
polling `jk run ls` in a loop:

```bash
jk run ls archiver | head -2                 # find the running build number
jk run view archiver <build> --wait          # blocks until it finishes, prints the result
jk log archiver <build> | tail -50           # on failure
```

Only redeploy once that reports SUCCESS: the webhook pulls `:latest`, so firing it against
a build still in progress deploys the previous image.
- Changes to `worker-common/` trigger rebuilds of all workers

### Docker builds

```bash
cd <service> && docker build -t dockerregistry.icomb.place/archiver/<service>:latest .
```

Workers that depend on `worker-common` use a Dockerfile context from repo root:
```bash
docker build -f embed-worker/Dockerfile -t dockerregistry.icomb.place/archiver/embed-worker:latest .
```

## Conventions

- **ONLY the backend talks to PostgreSQL and archiver_store.** Workers, scrapers, and frontend communicate exclusively via the backend HTTP API.
- ISO 639-1 language codes everywhere (2-char: de, cs, en)
- Job kinds: `ocr_page_mistral`, `ocr_page_claude`, `ocr_page_qwen3vl`, `build_searchable_pdf`, `translate_page`, `translate_record`, `embed_record`, `match_persons`, `extract_entities`
- Record statuses: `ingesting`, `ingested`, `ocr_pending`, `ocr_in_progress`, `ocr_done`, `pdf_pending`, `pdf_done`, `translating`, `embedding`, `matching`, `entities_pending`, `entities_done`, `complete`, `error`
- Python linting: `ruff` (line-length 100, target py314)
- Java formatting: `spotlessApply` (Google Java Format)

## Machine-Readable API (`/api/v1/`)

Designed for LLM tool use — returns self-contained JSON with full text content and links.
Base URL: `https://archive.czernin.eu/api/v1`

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/archives` | List all archives |
| `GET /api/v1/documents/{id}` | Full document with all page OCR text and translations |
| `GET /api/v1/documents?archiveId=N&page=0&size=20` | Browse documents |
| `GET /api/v1/search?q=term&archiveId=N` | Search (title, description, ref code, OCR text) |
