# Transkribus support — API access to the recognition models

**To:** `support@readcoop.eu` · **sent 18 September 2026, Zimbra 464815**

Address not verified against prior correspondence; it is READ-COOP's published
support address.

## What prompted it

Driving Transkribus from the archive works only for models the API will start,
and those are not the models the Scholar plan was bought for:

| Route | Result |
|---|---|
| Metagrapho Processing API, `https://transkribus.eu/processing/v1` | **401** — not in Scholar |
| TrpServer, recognition with a **TrHtr** model | **403** — the same models run in the web app |
| TrpServer, **PyLaia** models | starts normally |
| **Text Titan II** (model 579509) | cannot be started through the API at all |

So the pages have to be uploaded by hand, recognised in the browser, and the PAGE
XML exported back — which is what the `import-transkribus` endpoint exists to
absorb, but it is not a process for thousands of pages.

The letter asks which plan gives API access to Text Titan II and the super
models, whether the 403 is deliberate or an account permission fault, and whether
there is a timetable or beta for the REST API that the documentation lists as
"coming soon".

## Separately — a real fault of ours, found the same day

The registry row carried no `collId`, so the worker's submit sent 0 and every job
failed with **"Submit returned HTTP 400: Bad or no collection ID."** The account
has two collections; documents go in **2516426, "My Transcriptions"**.

`ai_implementation` was updated in place for both Transkribus rows, but **the
backend caches the registry at startup**, so the automated path stays broken until
the container restarts. A migration should carry the value so a rebuilt database
has it.

**And the order of operations is wrong.** `POST /api/admin/reocr-page` deletes
`page_text` for the page and *then* enqueues. When the job fails, the page is left
with no text and the old transcription is gone — `page_ocr_history` keeps metadata
only. That is how record 3515 pages 5-7 lost their text on 18 September. What was
lost was worthless (Mistral had read the signatures as "Schlitz Zimbabwe"), but
the next one might not be. The delete belongs in the worker, after a result comes
back.
