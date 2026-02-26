# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

Digital archive management system — scrapes, OCRs, translates, embeds, and indexes historical documents.

```
scrapers ──→ backend (Spring Boot) ←── ocr-worker-paddle
                  ↕↑                ←── pdf-worker
               PostgreSQL           ←── translate-worker
                  ↕↑                ←── embed-worker
            archiver_store
                  ↕↑
          frontend (SvelteKit)
```

All workers communicate **only** via the backend HTTP API (`/api/processor/*`).
They send/receive binaries and metadata over HTTP — no direct filesystem or DB access.
Only the backend touches PostgreSQL and archiver_store.

### Services

| Service | Stack | Description |
|---------|-------|-------------|
| backend | Java 25 / Spring Boot 4.0 | REST API, job orchestration, SSE events |
| frontend | SvelteKit + Tailwind v4 | UI with Verdant design system (`--vui-*` CSS vars) |
| worker-common | Python shared lib | Base `ProcessorClient`, SSE loop, job lifecycle helpers |
| ocr-worker-paddle | Python + PaddleOCR v3 | GPU-based OCR (nvidia runtime, 2 replicas) |
| pdf-worker | Python + reportlab | Builds searchable PDFs with invisible text overlay |
| translate-worker | Python + MarianMT | de→en and cs→en translation (nvidia runtime, 2 replicas) |
| embed-worker | Python + OpenAI | Chunks text, embeds via text-embedding-3-small, stores vectors |
| scraper-cz | Python | Czech National Archives (Zoomify tiles → PDF) |
| scraper-ebadatelna | Python | Czech Archive of Security Forces (auth required) |
| scraper-findbuch | Python | Austrian victims/property database (auth required) |
| scraper-oesta | Python | Austrian State Archives |
| scraper-matricula | Python | Matricula Online church records |

### Document Pipeline

```
ingesting → ocr_pending → ocr_done → pdf_pending → pdf_done → (future: entities)
```

When all OCR jobs complete for a record, `JobService.startPostOcrPipeline()` auto-enqueues:
- `build_searchable_pdf` (1 per record)
- `translate_record` (metadata translation, uses `record.metadata_lang`)
- `translate_page` (per page, auto-detects language from text)
- `embed_record` (chunks English text, generates embeddings)

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
make dev-frontend         # cd frontend && npm run dev
make test-backend         # cd backend && ./gradlew test
make test-scraper         # cd scraper-cz && pytest -v
make test-ocr             # cd ocr-worker-paddle && pytest -v
make test-pdf             # cd pdf-worker && pytest -v
make test-entity          # cd entity-worker && pytest -v
make test-frontend        # cd frontend && npm test
make test                 # all of the above
make lint                 # all linters (spotless, ruff, eslint+prettier)
```

### Single test / targeted commands

```bash
# Backend — single test class
cd backend && ./gradlew test --tests '*IngestControllerTest'

# Backend — format code
cd backend && ./gradlew spotlessApply

# Python workers — single test file
cd ocr-worker-paddle && pytest tests/test_something.py -v

# Python — format + lint fix
ruff check --fix scraper-cz/ && ruff format scraper-cz/

# Frontend — type check
cd frontend && npm run check
```

### Backend tests

Tests use Testcontainers (PostgreSQL) and REST Assured. Requires Docker running.
Config: `backend/src/test/resources/application-test.yml`.

## Backend Details

- **Package**: `place.icomb.archiver` — `controller/`, `service/`, `model/`, `dto/`, `repository/`, `config/`
- Spring Data JDBC (not JPA) — entities use `@Table`, no `@Entity`
- MapStruct for DTO mapping
- `BeanPropertyRowMapper` maps snake_case columns to camelCase Java fields
- Flyway migrations in `backend/src/main/resources/db/migration/V*.sql` (currently V1–V8)
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

- `service/JobService.java` — job orchestration, pipeline transitions
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
- Changes to `worker-common/` trigger rebuilds of all workers

### Docker builds

```bash
cd <service> && docker build -t dockerregistry.icomb.place/archiver/<service>:latest .
```

Workers that depend on `worker-common` use a Dockerfile context from repo root:
```bash
docker build -f ocr-worker-paddle/Dockerfile -t dockerregistry.icomb.place/archiver/ocr-worker-paddle:latest .
```

## Conventions

- **ONLY the backend talks to PostgreSQL and archiver_store.** Workers, scrapers, and frontend communicate exclusively via the backend HTTP API.
- ISO 639-1 language codes everywhere (2-char: de, cs, en)
- Job kinds: `ocr_page_paddle`, `build_searchable_pdf`, `translate_page`, `translate_record`, `embed_record`
- Record statuses: `ingesting`, `ingested`, `ocr_pending`, `ocr_done`, `pdf_pending`, `pdf_done`
- Python linting: `ruff` (line-length 100, target py310)
- Java formatting: `spotlessApply` (Google Java Format)

## Machine-Readable API (`/api/v1/`)

Designed for LLM tool use — returns self-contained JSON with full text content and links.
Base URL: `https://archiver.icomb.place/api/v1`

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/archives` | List all archives |
| `GET /api/v1/documents/{id}` | Full document with all page OCR text and translations |
| `GET /api/v1/documents?archiveId=N&page=0&size=20` | Browse documents |
| `GET /api/v1/search?q=term&archiveId=N` | Search (title, description, ref code, OCR text) |
