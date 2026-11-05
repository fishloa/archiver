# Multi-language PDF export: implementation report

Branch `feat/multilang-pdf-export`, worktree `/Volumes/External/Projects/archiver/.claude/worktrees/agent-a5286a4adec748c14`.
Nothing pushed, tagged or deployed. No production access. CHANGELOG.md untouched.

## Commits (in order)

1. `36e341a` Give page_translation a language, keep every existing reader on English (Task 1, migration V27)
2. `7e7e2a3` Translator takes a target language; share the synchronous translate call (Task 2)
3. `bff9af8` Strip a preamble that precedes a code fence; recognise 'translated text' and Czech/German introductions (coordinator's extra item)
4. `1bce58f` Translate a page into Czech or German once, on demand, and keep it (Task 3)
5. `dfcde6d` Exports carry a language: parsed, fingerprinted, reused and named by it (Task 4)
6. `f519486` Render translation and side-by-side PDFs in English, German or Czech (Task 5)
7. `f63482b` Translate missing pages while building a Czech or German export (Task 6)
8. `e3f8f57` Accept and report lang on the PDF export endpoint and MCP tools (Task 7)
9. `97d620d` Choose English, German or Czech for translation and side-by-side PDFs (Task 8, including the Layout section)
10. `6b2601e` Document PDF exports in German and Czech (Task 9)
11. `8231713` Report on the multi-language PDF export implementation
12. `3238fef` A Czech or German translation counts only while it matches the current transcription (review 1)
13. `474cb6b` Check an export against the translation id it was requested with, not a clock (review 2)
14. `b996536` Keep a translation's opening label when the source document opens with one (review 3)
15. report update

Task 10 changed no files (no lint fixes were needed).

## Test results

- Backend, full `./gradlew test` (run after Task 1 and again at the end): BUILD SUCCESSFUL, 844 tests, 0 failures, 0 errors, 0 skipped. `spotlessCheck` clean.
- Targeted: ChatTranslatorTest 20, SyncTranslateWorkerTest 2, TranslateMissingTest 8, TranslationServiceTest 31, PdfExportQueueTest 57, PdfExportServiceTest 22, PdfExportWorkerTest 14, PdfExportWorkerFailureTest 5, PdfExportTranslationTest 7, PdfExportApiTest 26, McpContentToolsTest 11. All green.
- Frontend: `bun run test` 134 passed (9 files); i18n completeness PASSED; `bun run check` 0 errors (3 pre-existing warnings in unrelated files); no `style="` in the touched files.

## Not hardware/production verified

The spec's release step (a real `cs` export of one small record on production) is not done; instructed not to deploy. All verification is host tests with Testcontainers and a WireMock model. The Layout in Task 8 was not looked at in a browser (the dev server needs a backend); it is verified only by svelte-check and by reading the markup. Please eyeball `/records/{id}` before release.

## Deviations from the plan, and why

1. **Plan and spec files were untracked in the main checkout**, so absent from the worktree. I copied them into `docs/superpowers/{specs,plans}/` in the worktree and left them untracked (not committed), apart from this report.
2. **Extra `page_translation` reader found**: `ManualCorrectionService.translatedWithoutText()` (two subqueries on `page_translation`, using `text_en`) was not in the plan. Renamed to `text` and pinned to `lang = 'en'` in Task 1. Everything else matched the plan; `AiAdminController` (usage guard) and `PipelineStateMachine` (re-OCR delete) left unfiltered by ruling. The Task 10 self-check grep over `page_translation` statements was done and every statement has a lang condition or is one of those two.
3. **Coordinator's extra item** (separate commit `bff9af8`, with its own tests): `ChatTranslator.parse` is now `stripPreamble(unwrapCodeFence(stripPreamble(content)))`, so a preamble before a fence is removed first. The "here is / here's / below is" pattern also accepts `translated text` and `translated version`; added Czech ("Zde je / Toto je [český|německý|anglický] překlad / přeložený text ...:") and German ("Hier ist / Nachfolgend die [deutsche|tschechische|englische] Übersetzung / übersetzte Text ...:") introductions. Tests: the exact production input `Here is the translated text:\n\n```markdown\n...\n```` yields the inner text with neither preamble nor fence; plus the new introduction forms. The English request body is unchanged (still asserted byte-identical).
4. **Test adjustment** in the plan's `aCzechExportShowsTheStoredCzechAndSaysSo` / `aGermanExportOfAGermanRecordShowsTheTranscription`: the cover sheet wraps lines ("... is\nthe authoritative text"), so the cover text is whitespace-normalised before the `contains` assertions. Production code is as the plan specifies.
5. **Frontend formatting**: the repo has no prettier parser for `.svelte` and the TS files are not prettier-formatted (tab style predates it), so `prettier --write` as the plan suggests rewrote whole files. I reverted that and kept only the targeted edits, matching the existing style. `eslint .` cannot run in this checkout (no flat config; ESLint 9 reports a config migration error), so lint was not run for the frontend; that is pre-existing, not caused by this change.
6. Minor: no comments added; plan's `// ...` comments in snippets were kept only where the plan gave Javadoc/spec-level reasons. The page's `exportVariant` value changed from `'english'` to `'translation'` as the plan says (backend still accepts `english`).

## Task 8 layout as built (record page)

- Slim bar above the grid, right-aligned, shown when `record.sourceUrl || isAdmin`: Source link and the admin reset dropdown (behaviour unchanged).
- Below it a `grid grid-cols-1 md:grid-cols-2 gap-4 mb-6`, shown when there is a translation status or pages:
  - Left card: Translation quality panel, same content and `tq-*` classes (heading, status note, Improve translation form, hint).
  - Right card: Export. Heading with the `Download` icon and `record.exportTitle`. Labelled fields: Pages (input), Format (Original scans / Translation / Side by side), Language (English / German / Czech, only when format is not Original). Below them the download button, plus the kept-pages button and its clear button when pages are kept. When there is no translation status, the Export card spans both columns (`md:col-span-2`); the card is not rendered when the record has no pages.
- New i18n keys in en/de/cs: `record.exportTitle`, `record.exportPagesLabel`, `record.exportFormatLabel`, `record.exportLangLabel`, `record.exportLangTitle`, `record.exportLangEn/De/Cs`. Only existing utility classes and `vui-*` classes; no inline styles; no new CSS.
- The re-indented admin dropdown and Translation quality markup account for most of that file's diff; the content is moved, not changed.

## Unfinished / caveats

- No real-hardware or production check of a `cs` export (see above).
- Frontend lint (`eslint`) not runnable here; `make lint` for the frontend will need the repo's own environment.
- Concurrency lock in `translateMissing` is JVM-local (single backend assumption, as the plan states).

## Review fixes (after the first report)

V27 was edited in place for the two schema changes (never applied to a persistent database); no V28.

1. **Stale cs/de translations** (`3238fef`). `page_translation.source_md5` (nullable, set for lang <> 'en' from the md5 of the text_raw that was sent). A cs/de row is present and rendered only when some `page_text.text_raw` of the page still hashes to it: enforced in `translateMissing`, `bestByRecord`, `modelBreakdown` and the cover sheet's translated count, so no call site of re-OCR, correction or advance needs to know. Rows without it (including older ones) count as missing and are re-translated; the upsert replaces the row under the (page_id, lang, model) key. English rows are untouched. `recordIn(pageId, lang, model, text)` still works and takes the md5 from the current page text; `translateMissing` passes the md5 of the exact text it sent, so a correction made during the call is not mistaken for a match. Tests: TranslateMissingTest (translate, correct text_raw, asked again, new text stored and rendered) and PdfExportTranslationTest (the same through ManualCorrectionService and a full export). Existing tests that insert cs rows by SQL now set source_md5.
2. **Fingerprint cutoff race** (`474cb6b`). The time cutoff is replaced by an id cutoff: `pdf_export.translation_cutoff` holds `max(page_translation.id)` read once in `request()` and used both for the request fingerprint and, via `Claimed.translationCutoff`, the worker's pre-build check (`Claimed.requestedAt` is gone). The two computations therefore measure the same rows however exports interleave. Tests: a row committed after the cutoff does not change the fingerprint at that cutoff; the claimed cutoff equals the one at request; the earlier sibling-export test uses the new cutoff. Residual: a row whose id was allocated before the cutoff but committed after it would still show; translation rows are written by single autocommit statements, so that window is a few microseconds.
3. **Real heading deleted** (`b996536`). `Translator.parse(body, sourceText)` (default delegates to `parse(body)`); `ChatTranslator` strips a bare-label first line ("### Translation", "Übersetzung:", "Překlad:", etc.) only when the source's first line is not itself such a label (en/de/cs, loosely matched: with or without #, asterisks, colon). "Here is the ... translation:" style introductions stay unconditional. Sync worker, the on-demand export path and the batch stage pass the source (the batch stage reads text_raw in `applyResult`). Request bodies unchanged. Tests: kept when source opens with a label, stripped when it does not or the source is unknown, spoken introductions stripped regardless.

Final verification: full backend suite 851 tests, 0 failures/errors/skipped; spotless clean. Frontend: vitest 134 passed, svelte-check 0 errors.
