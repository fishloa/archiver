---
name: archiver-api
description: Use when reading or changing anything in the running archiver — records, pages, OCR text, jobs, the pipeline. Gives the admin and read API, and the auth recipe for calling it from the CLI. Never open psql against production.
---

# Archiver API

## Reach for the MCP first

The archive exposes its own MCP server, `claude_ai_Cz_Archives`. Those tools are
the first choice — no tokens, no ssh, and the tool description carries the
guidance with it.

| MCP tool | For |
|---|---|
| `search_documents`, `semantic_search`, `browse_documents`, `get_document` | finding and reading records |
| `list_archives`, `search_family_tree`, `get_person`, `find_people_in_document` | archives, people |
| `list_jobs` | the queue, and where a job id comes from |
| `hold_record` | **the panic button** — stop a record's AI processing, or release it |
| `cancel_job` | one queued job, by id |
| `reocr_page` | read one page again on a chosen engine |

The last three are administrators only and fail closed: no provable
`ROLE_ADMIN`, no action. `reocr_page` also refuses a record that is on hold,
rather than queueing work that would run the moment the hold lifts.

The HTTP API below is for everything the MCP does not carry — exports, gates,
bulk operations, the Transkribus import, and the release check.

**The database is not an interface.** Reads and writes go through the API. If
something needed is missing from it, add the endpoint — that is a reason to write
code, not to open `psql`. (See the standing rule in memory.)

## Auth

The public URL sits behind OAuth2 and answers `401` to a script. Two bearer
tokens live in the backend container's environment, and the backend is reachable
inside the Docker network on zelkova:

```bash
# read access (PROCESSOR role): /api/v1/**, /api/records/**
tok() { ssh zelkova 'docker inspect archiver-backend-1 --format "{{range .Config.Env}}{{println .}}{{end}}" \
  | grep -E "^ARCHIVER_PROCESSOR_TOKEN=" | cut -d= -f2-'; }

# admin access (ADMIN role): everything under /api/admin/**
atok() { ssh zelkova 'docker inspect archiver-backend-1 --format "{{range .Config.Env}}{{println .}}{{end}}" \
  | grep -E "^ARCHIVER_ADMIN_TOKEN=" | cut -d= -f2-'; }
```

Call it from zelkova, where the container's IP resolves:

```bash
ssh zelkova 'atok=$(docker inspect archiver-backend-1 --format "{{range .Config.Env}}{{println .}}{{end}}" \
  | grep -E "^ARCHIVER_ADMIN_TOKEN=" | cut -d= -f2-); \
  curl -s -H "Authorization: Bearer $atok" "http://10.0.9.3:8080/api/admin/jobs?recordId=4006"'
```

`10.0.9.3` is the backend container; confirm with `docker inspect archiver-backend-1`.
Port 8099 is the nginx/OAuth2 front door and will answer 401 to a token.

## Reading

| Call | Gives |
|---|---|
| `GET /api/version` | deployed tag and commit — the check after a release; no auth |
| `GET /api/v1/documents/{id}` | a record with every page's OCR text and translation |
| `GET /api/v1/documents?archiveId=N&page=0&size=20` | browse |
| `GET /api/v1/search?q=term&archiveId=N` | title, description, ref code and OCR text |
| `GET /api/v1/archives` | the archives |
| `GET /api/semantic-search?q=…` | vector search over the original text, cross-lingual |

## Jobs and the queue

| Call | Does |
|---|---|
| `GET /api/admin/jobs?recordId=&pageId=&kind=&status=&limit=` | lists jobs, newest first; this is how a job id is found |
| `POST /api/admin/cancel-jobs?jobId=&reason=` | stops **one** job. Pending and claimed only; a finished job is untouched |
| `GET /api/admin/stats` | queue depth and pipeline state |
| `POST /api/admin/audit` | runs the audit pass that unsticks records |

There is deliberately no "cancel everything matching". A record's queued work is
stopped by holding the record, below — cancelling its jobs without holding it
only invites the state machine to enqueue them again.

## Freezing a record (the panic button)

```bash
# stop all AI processing on a record; queued jobs wait rather than dying
POST /api/admin/records/{id}/ai-hold?reason=bad+re-OCR

# ...and throw away what is already queued, when that work is the problem
POST /api/admin/records/{id}/ai-hold?reason=…&cancelQueued=true

# let it run again
POST /api/admin/records/{id}/ai-hold?hold=false
```

`ai_held_at` is a condition in all three claim queries, so **no** worker — single
or batch — will claim a held record's jobs. Translation, embedding and matching
are charged per call; this is what stops a broken record spending money while it
is being put right.

## OCR

```bash
# re-read one page; engine is transkribus | mistral | any registered OCR job kind
POST /api/admin/reocr-page?recordId=4006&seq=28&engine=mistral&andThen=full
POST /api/admin/reocr-page?pageId=147299&engine=transkribus&htrId=263129
```

- `andThen=full` carries that page through translation, the record's PDF and
  re-embedding. `andThen=none` leaves it.
- `pageClass=print` picks the typewriter model rather than the handwriting one.
- **Which engine.** Print and typescript → **mistral**, by a wide margin
  (record 4006 page 28: 3,406 characters against 491). Handwriting →
  **transkribus**. Sending a handwriting model at a typescript loses most of the
  page.

## Ingesting a record

```bash
POST /api/ingest/records           # create or update, matched on sourceSystem + sourceRecordId
POST /api/ingest/records/{id}/pages?seq=N    # multipart "image"
POST /api/ingest/records/{id}/text-pdf       # born-digital PDF: keeps its text layer, skips OCR
POST /api/ingest/records/{id}/complete       # hands over to the state machine
```

**Say which engine and which translation at create time.** Both are fields on the
record, and both are cheaper to get right once than to repair per page:

| Field | Values | Effect |
|---|---|---|
| `ocrEngine` | `ocr_page_mistral`, `ocr_page_transkribus`, … | the job kind every page of this record is queued under; null takes the deployment default |
| `translationQuality` | `bulk` (default), `best` | which model translates, one job per page of one kind |

`ocrEngine` is checked against the registered OCR rows when the record is created:
an engine nothing claims is a **400** naming the kinds that exist, rather than a
queue of jobs no worker will ever take. Enabled rows count, credentialled or not —
an ingest may run long before the pages are read.

Print and typescript → **mistral**. Handwriting → **transkribus**, and read the
next section before choosing it.

## Transkribus: upload by machine, Run by hand, import by API

The recognition models cannot be started from the API on this account — the
TrpServer answers *"You are not allowed for TrHtr Recognition!"* (403) to the
super models, and the PyLaia endpoint refuses them as not PyLaia models. So the
round trip has three steps and the middle one is a person:

```bash
POST /api/admin/transkribus/upload?recordId=4021          # every page
POST /api/admin/transkribus/upload?recordId=4021&seq=3    # one page
#   → uploads each image as rec<recordId>_seq<seq>.jpg and returns its docId
#   ... press Run in the Transkribus web app ...
POST /api/admin/import-transkribus?docId=19063761
```

The file name is the only thing tying a transcription back to a page, which is
why uploading has an endpoint rather than a shell script.

Setting `ocrEngine: ocr_page_transkribus` at ingest queues
`ocr_page_transkribus` jobs, and **those jobs currently fail**: the worker is
wired to the Metagrapho client, which this plan does not include. Until that is
fixed the hint is a statement of intent, and the upload endpoint above is the
working route.

## Transkribus import

```bash
POST /api/admin/import-transkribus?docId=18941356          # one document
POST /api/admin/import-transkribus?all=true                # the whole collection — say so
POST /api/admin/import-transkribus?docId=…&overwrite=true  # allow a shorter transcript to win
GET  /api/admin/transkribus/models?lang=cs&docType=handwritten
```

Pages are matched to the archive by the file name they were uploaded under,
`rec<recordId>_seq<pageSeq>.jpg`. Three refusals are built in, and each exists
because it went wrong once: no `docId` and no `all=true` is a 400; a **shorter**
transcript from a **different** engine needs `overwrite=true`; a transcript
identical to the stored text is skipped rather than rewritten.

## Gates and bulk operations

| Call | Does |
|---|---|
| `POST /api/admin/gates/{kind}` body `{"paused": true}` | pauses a job kind; its jobs queue |
| `POST /api/admin/gates/stage/{stage}` | same for a pipeline stage |
| `POST /api/admin/records/reset-pipeline` | re-runs a record's pipeline from a stage |
| `POST /api/admin/enqueue-reocr?recordId=&limit=` | bulk re-OCR — **never** without being asked |
| `POST /api/admin/reset-embeddings?confirmChunks=N` | clears embeddings; N must match the count |

## Exports

```bash
GET /api/records/{id}/export-pdf?pages=46-50&variant=original
GET /api/records/{id}/export-pdf?pages=1,5&variant=side-by-side
GET /api/records/{id}/pdf
```

`variant` is `original`, `english` or `side-by-side`. Ranges and comma lists both
work, and the export carries the searchable text layer — use it rather than
assembling images by hand.

## After a release

```bash
jk run ls archiver | head -2                # find the build
jk run view archiver <n> --wait             # block on it
curl -s https://archive.czernin.eu/api/version   # or via the container, 401 outside
ssh zelkova 'docker ps --filter name=archiver --format "{{.Names}}\t{{.Status}}"'
```

Only a git tag moves `:latest` and deploys production, and the tag must sit on
its own new commit — Jenkins tracks by SHA, so a tag on an already-built commit
triggers nothing.
