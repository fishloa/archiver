# Archiver

Digital archive management system — scrapes, OCRs, translates, and indexes historical documents.

## Architecture

```
scraper-cz ──→ backend (Spring Boot) ←── ocr-worker-paddle
                  ↕↑                 ←── pdf-worker
               PostgreSQL            ←── translate-worker
                  ↕↑
            archiver_store
                  ↕↑
          frontend (SvelteKit)
```

All workers communicate **only** via the backend HTTP API (`/api/processor/*`).
They send/receive binaries and metadata over HTTP — no direct filesystem or DB access.
Only the backend touches PostgreSQL and archiver_store.

### Services

| Service | Language | Port | Description |
|---------|----------|------|-------------|
| backend | Java 21 / Spring Boot | 8080 (internal), 8098 (host) | REST API, job orchestration, SSE events |
| frontend | SvelteKit + Tailwind v4 | 3000 (internal), 8099 (host) | UI with Verdant design system (`--vui-*` CSS vars) |
| scraper-cz | Python | — | One-shot scraper for Czech National Archives |
| ocr-worker-paddle | Python + PaddleOCR v3 | — | GPU-based OCR (nvidia runtime) |
| pdf-worker | Python + reportlab | — | Builds searchable PDFs with invisible text overlay |
| translate-worker | Python + MarianMT | — | de→en and cs→en translation (Helsinki-NLP models) |

### Document Pipeline Flow

```
ingesting → ocr_pending → ocr_done → pdf_pending → pdf_done → (future: entities)
```

When all OCR jobs complete for a record, `JobService.startPostOcrPipeline()` auto-enqueues:
- `build_searchable_pdf` (1 per record)
- `translate_record` (metadata translation, uses `record.metadata_lang`)
- `translate_page` (per page, auto-detects language from text)

### Language Handling

- `record.lang` = content language (ISO 639-1, e.g. "de"), passed to OCR engine
- `record.metadata_lang` = catalog language (e.g. "cs"), used for title/description translation
- DB enforces 2-char ISO codes via CHECK constraints
- Scraper sets both independently; don't assume they're the same
- Translation skipped if lang = "en"

## Development

### Backend (Spring Boot)

```bash
cd backend && ./gradlew bootRun
```

- Spring Data JDBC (not JPA) — entities use `@Table`, no `@Entity`
- Flyway migrations in `src/main/resources/db/migration/V*.sql`
- DB: PostgreSQL (see deploy/docker-compose.yml for connection details)
- `BeanPropertyRowMapper` maps snake_case columns to camelCase Java fields

### Frontend (SvelteKit)

```bash
cd frontend && npm install && npm run dev
```

- Tailwind v4 + Verdant UI design system
- SSE via `EventSource('/api/records/events')` with debounced `invalidateAll()`
- Icons from `lucide-svelte`

### Workers (Python)

All workers follow the same pattern:
1. Connect to backend SSE at `/api/processor/jobs/events`
2. On `job_enqueued` event, claim job via `POST /api/processor/jobs/claim`
3. Process, then complete/fail via `POST /api/processor/jobs/{id}/complete` or `/fail`
4. Auth via `Bearer {PROCESSOR_TOKEN}` header

### Building Docker Images

```bash
cd <service> && docker build -t dockerregistry.icomb.place/archiver/<service>:latest .
```

## Deployment

- **CI/CD**: Jenkins at `ci.icomb.place`, Jenkinsfile with path-based change detection
- **Registry**: `dockerregistry.icomb.place` (Nexus)
- **Hosting**: Portainer stack #183 on zelkova (endpoint 2)
- **Redeploy**: `curl -X POST https://docker.icomb.place/api/stacks/webhooks/7cab44a1-fefe-4184-bc65-336c45468a45`

## Key Files

- `backend/src/main/java/.../service/JobService.java` — job orchestration, pipeline transitions
- `backend/src/main/java/.../service/IngestService.java` — record creation, OCR job enqueuing
- `backend/src/main/java/.../controller/ProcessorController.java` — worker API endpoints
- `backend/src/main/java/.../controller/ViewerController.java` — frontend API (search, pipeline stats)
- `backend/src/main/java/.../controller/CatalogueController.java` — record listing, filtering, SSE
- `deploy/docker-compose.yml` — production compose file
- `Jenkinsfile` — CI/CD pipeline with parallel builds

## Conventions

- **ONLY the backend talks to PostgreSQL and archiver_store.** Workers, scrapers, and frontend communicate exclusively via the backend HTTP API. Never give workers direct DB or filesystem access.
- ISO 639-1 language codes everywhere (2-char: de, cs, en)
- Job kinds: `ocr_page_paddle`, `build_searchable_pdf`, `translate_page`, `translate_record`
- Record statuses: `ingesting`, `ingested`, `ocr_pending`, `ocr_done`, `pdf_pending`, `pdf_done`
- Workers use `HOME=/tmp` env var to avoid PaddleX/HuggingFace init issues with non-root users
