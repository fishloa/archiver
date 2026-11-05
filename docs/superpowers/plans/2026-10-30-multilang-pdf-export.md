# Multi-language PDF export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The translation PDF and the side-by-side PDF can be made in English, German or Czech, chosen per export. Each page's translation into a language is paid for once and kept in the existing `page_translation` table.

**Architecture:** `page_translation` gains `lang`, and `text_en` is renamed `text`. Every existing reader and writer is pinned to `lang = 'en'`, so English is untouched. `pdf_export` gains `lang`. Before building a `cs` or `de` export, `PdfExportWorker` calls the new `TranslationService.translateMissing`. That method sends each selected page that has no translation in that language to the best configured TRANSLATION model, once, through the synchronous HTTP exchange taken out of `SyncTranslateWorker`, and stores the result. `PdfExportService` then renders from the stored rows. The existing ranking (`ai_implementation` order) picks the best model per page and language. A page in the record's own language shows its transcription, and nothing is paid for.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring `JdbcTemplate`, Flyway, PDFBox 3, JUnit 5 + Testcontainers (pgvector/pg18) + WireMock (already a test dependency, see `SemanticSearchTest`), SvelteKit + Svelte 5 runes, vitest, bun.

**Spec:** `docs/superpowers/specs/2026-10-30-multilang-pdf-export.md`. Read it first. It is the binding authority, and this plan records the choices it left open.

## Global Constraints

- Never edit an applied migration. This plan's migration is `V27__multilang_translation.sql`. `V26__user_activity.sql` is the latest (V21-V26 were added after this plan was written). Line numbers quoted in this plan date from 30 Oct 2026: locate code by content, not by line.
- Java is formatted by Spotless with Google Java Format: run `cd backend && ./gradlew spotlessApply` before every commit.
- Backend tests use Testcontainers (PostgreSQL) and Java `HttpClient`, never REST Assured.
- Use bun, never npm: `bun install`, `bun run`, `bunx`.
- Zero inline `style=""` attributes, anywhere.
- Use Verdant design-system classes (`vui-*`, `--vui-*`) verbatim. Do not override, extend or duplicate them.
- Never write comments that explain what code does. Comments only for what the code cannot say.
- English behaviour is byte-identical: `page_text.text_en`, the batch and sync translate workers, the ranking, search, the viewer, hand corrections, the state machine and the English exports produce exactly what they do today.
- The default language is `en`, so every existing caller behaves exactly as now.
- Accepted languages are exactly `en`, `de`, `cs`. Anything else is refused with a message naming them.
- Commit messages carry no `Co-Authored-By` line and no attribution of any kind.
- Commit working code before any refactor.
- Touch only what the task names.

## Decisions the spec left open (settled here)

1. **Which translator.** The export uses `AiRegistry.best(AiCapability.TRANSLATION)`, the top-ranked enabled row whose credential is present (in production that is `mistral:mistral-medium-latest`, key `MISTRAL_API_KEY`). It is wrapped in `new ChatTranslator(registration, registry.credential(registration))`, and the call is the same synchronous chat-completions POST that `SyncTranslateWorker` makes, to `endpoint() + endpointPath()` with `Authorization: Bearer <key>`. That exchange moves into `SyncTranslateWorker.translate(...)` so both callers share it. The provider's batch style is ignored: Mistral's chat endpoint answers synchronously, and the batch file only wraps the same body. The translation is stored under that model, which also ranks first, so no later export or upgrade outranks it by accident.
2. **The English request is byte-identical.** `ChatTranslator.instruction("en")` produces exactly the old `INSTRUCTION` string. `Translator.request(text, sourceLang)` stays, as a default method that delegates with `"en"`, so `TranslateBatchStage` and `SyncTranslateWorker` send unchanged bodies.
3. **Re-OCR deletes every language.** `PipelineStateMachine` line 499 (`DELETE FROM page_translation WHERE page_id = ?`) keeps no language filter, because a translation of replaced text is wrong in every language. The `AiAdminController` delete guard (line 325) also keeps counting rows in every language: a model with any stored output must stay ranked. Both statements are textually unchanged. Every other reader and writer is pinned to `'en'`.
4. **Fingerprint.**
   - Both translation parts of the existing SQL (record-wide provenance, and per selected page) are filtered to the export's language. The English fingerprint is therefore byte-identical to today, and Czech rows do not disturb it.
   - For a language other than English, the language code and an md5 of the selected pages' `text_raw` are appended. A German export of a German record shows `text_raw`, so a hand correction must invalidate it.
   - The worker's pre-build check passes the export's `created_at` as a cutoff, which excludes rows in that language created after the request. Without the cutoff, a second export queued behind a translating one would fail as "pages changed", because translations it did not ask for were stored.
   - After translating, the worker re-fingerprints the row (`refingerprint`), so an identical later request reuses the finished file.
5. **`english` is the alias.** `english` means `translation` with `lang=en`. `english` with any other lang is refused with a 400. For `original`, any lang is accepted and stored as `en`, so it is the same export.
6. **API names.** An `en` translation export still reports `variant: "english"`, so existing callers see what they saw. A `de`/`cs` export reports `"translation"`. Every view gains `lang`, and the file response carries `Content-Language`.
7. **Java enum.** `PdfExportService.Variant.ENGLISH` is renamed `TRANSLATION`. The database value stays `'english'`, so the `pdf_export.variant` CHECK constraint is not touched.
8. **Paying once under concurrency.** `translateMissing` holds a per-(record, language) JVM lock while it finds and translates the missing pages. Two exports building at once therefore never send the same page twice. This relies on the single-backend assumption that `PdfExportWorker` already documents.
9. **Blank pages and empty answers.** A blank `text_raw` is never sent and nothing is stored. The export shows it as `[no text]`, as today. If the model returns an empty answer for a non-blank page, nothing is stored, the page falls back to its transcription with `[not translated - original text]`, and the next export asks again.
10. **No English fallback in other languages.** A `cs`/`de` export never falls back to `page_text.text_en`. It falls back only to the transcription, and marks that fallback.
11. **Labels are in English**, like the cover sheet: `Czech translation` or `German transcription` at the top of each text page and text column. English pages carry no label, as now.
12. **CHANGELOG.md is not edited.** Its own rules put an entry in the tagged release commit, and the release is the user's call.

## Review Focus

These are the failure modes most likely to slip past a straight reading of the tasks. Each has a test in the task that owns the code.

1. **A Czech row read as English.** A `page_translation` query missing `lang = 'en'` lets a Czech row by a better-ranked model become the English text, the cached `text_en`, or the cover's model. *Task 1: `aCzechTranslationIsNeverTakenForTheEnglishOne`, `aPageWithOnlyACzechTranslationKeepsItsEnglishCache`. Task 5: `theEnglishExportsAreUnchangedByCzechRows`.*
2. **A Czech export showing English.** The existing renderer falls back to `page_text.text_en`, and an untranslated page in a Czech PDF would silently read in English. *Task 5: `aCzechExportOfAnUntranslatedPageShowsTheTranscriptionNotTheEnglish`.*
3. **A queued sibling export failing as "pages changed".** The first export's translations change the fingerprint of the second export, which is already queued. *Task 4: `aTranslationMadeAfterTheRequestIsNotAChangeToThePages`. Task 6: `anExportQueuedBehindOneThatTranslatesStillBuilds`.*
4. **Paying twice.** Two workers building `translation/cs` and `side-by-side/cs` of one record at the same moment both send every page. *Task 6: `twoExportsBuildingAtOnceTranslateEachPageOnce`.*
5. **A hand correction of the English rewriting the Czech.** `ManualCorrectionService`'s `replace(text, …)` over every row of the page would edit the Czech text too. *Task 1: `anEnglishCorrectionLeavesOtherLanguagesAlone`.*

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V27__multilang_translation.sql` (create) | `page_translation.lang`, `text_en` → `text`, unique (page_id, lang, model); `pdf_export.lang` |
| `backend/src/main/java/place/icomb/archiver/model/PageTranslation.java` (modify) | Entity gains `lang`; `textEn` → `text` |
| `backend/src/main/java/place/icomb/archiver/repository/PageTranslationRepository.java` (modify) | `upsert` takes lang; every query pinned to `'en'` |
| `backend/src/main/java/place/icomb/archiver/service/TranslationService.java` (modify) | English queries pinned to `'en'`; `bestByRecord`, `modelBreakdown(…, lang)`, `recordIn`, `translateMissing` |
| `backend/src/main/java/place/icomb/archiver/service/ManualCorrectionService.java` (modify) | Corrections of `text_en` touch English rows only |
| `backend/src/main/java/place/icomb/archiver/controller/ViewerController.java` (modify) | `getTextEn()` → `getText()` |
| `backend/src/main/java/place/icomb/archiver/ai/Translator.java` (modify) | `request(text, sourceLang, targetLang)`; old signature defaults to `en` |
| `backend/src/main/java/place/icomb/archiver/ai/ChatTranslator.java` (modify) | Instruction parameterised by target; Czech/German preambles stripped |
| `backend/src/main/java/place/icomb/archiver/service/SyncTranslateWorker.java` (modify) | The HTTP exchange becomes `public static translate(...)`, reused by the export |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java` (modify) | Lang parsing, fingerprint by lang with cutoff, reuse by lang, file names, `refingerprint` |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java` (modify) | `Variant.TRANSLATION`; renders in a language; labels |
| `backend/src/main/java/place/icomb/archiver/service/CoverSheetService.java` (modify) | Cover names the text's language, transcription or translation, and its models |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java` (modify) | Fingerprint check with cutoff; translates missing pages; passes lang to the build |
| `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java` (modify) | `lang` in, `lang` out, `Content-Language`, language file names |
| `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java` (modify) | `export_pdf` takes `lang`; `get_pdf_export` and `download_pdf` report it |
| `backend/src/test/java/place/icomb/archiver/TranslatorStub.java` (create) | Points the registry at a WireMock chat endpoint; counts calls |
| `backend/src/test/java/place/icomb/archiver/service/SyncTranslateWorkerTest.java` (create) | The shared synchronous call |
| `backend/src/test/java/place/icomb/archiver/service/TranslateMissingTest.java` (create) | On-demand translation, pay-once, failure |
| `backend/src/test/java/place/icomb/archiver/service/PdfExportTranslationTest.java` (create) | Worker end to end with a stubbed model |
| Existing tests (modify) | Column rename; new cases in `TranslationServiceTest`, `ManualCorrectionTest`, `ChatTranslatorTest`, `PdfExportQueueTest`, `PdfExportServiceTest`, `PdfExportWorkerFailureTest`, `PdfExportApiTest`, `McpContentToolsTest`, `McpDownloadPartsTest` |
| `frontend/src/lib/pdf-export.ts` (modify) | `exportRequest` carries `lang` |
| `frontend/src/lib/components/PdfExportButton.svelte` (modify) | `lang` prop |
| `frontend/src/routes/records/[id]/+page.svelte` (modify) | Language choice next to the variant |
| `frontend/src/lib/messages/{en,de,cs}.ts` (modify) | Four new keys |
| `frontend/tests/{pdf-export,PdfExportButton}.test.ts` (modify) | Lang cases |
| `CLAUDE.md`, `.claude/skills/archiver-api/SKILL.md` (modify) | Document `lang` |

Unchanged by ruling: `PipelineStateMachine.java:499` and `AiAdminController.java:325`. They read no `text_en` and deliberately span every language.

---

## Task 1: Migration V27 and the English-only sweep

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__multilang_translation.sql`
- Modify: `backend/src/main/java/place/icomb/archiver/model/PageTranslation.java:15-43`
- Modify: `backend/src/main/java/place/icomb/archiver/repository/PageTranslationRepository.java:19-64`
- Modify: `backend/src/main/java/place/icomb/archiver/service/TranslationService.java:78-79,98-102,122,145,176,193-199,218-219`
- Modify: `backend/src/main/java/place/icomb/archiver/service/ManualCorrectionService.java:111-121`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/ViewerController.java:525`
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java:169-178` (column rename only; the lang filter comes in Task 4)
- Modify (rename only): `backend/src/test/java/place/icomb/archiver/{PdfFixtures.java:82, mcp/McpTranslationTest.java:98-100, controller/AiAdminControllerTest.java:213, controller/ManualCorrectionTest.java:94,137,235, controller/PageMoveTest.java:154, controller/TranslationUpgradeTest.java:123, service/TranslationServiceTest.java:110-112, service/AdvanceSinglePageTest.java:202, service/PdfExportQueueTest.java:217,227,293, service/PdfExportServiceTest.java:164,334-337,409-411}`
- Test: `backend/src/test/java/place/icomb/archiver/service/TranslationServiceTest.java`, `backend/src/test/java/place/icomb/archiver/controller/ManualCorrectionTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - DB: `page_translation(id, page_id, model, text, created_at, lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en','de','cs')))`, `UNIQUE (page_id, lang, model)`; `pdf_export.lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en','de','cs'))`
  - `void PageTranslationRepository.upsert(Long pageId, String lang, String model, String text)`
  - `String PageTranslation.getLang()`, `String PageTranslation.getText()` (replaces `getTextEn()`)

- [ ] **Step 1: Write the failing tests**

In `TranslationServiceTest.java`, add these imports:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
```

Add after `recordIdOf` (line 229):

```java
  // -------------------------------------------------------------------------
  // Other languages share the table and never reach English
  // -------------------------------------------------------------------------

  private void czech(String model, String text) {
    jdbc.update(
        "INSERT INTO page_translation (page_id, lang, model, text, created_at)"
            + " VALUES (?, 'cs', ?, ?, now())",
        pageId,
        model,
        text);
  }

  @Test
  void aCzechTranslationIsNeverTakenForTheEnglishOne() {
    translation("mistral-small-latest", "cheap");
    czech("mistral-medium-latest", "Český překlad");

    assertThat(translationService.bestEnglish(pageId)).isEqualTo("cheap");
    assertThat(translationService.bestModel(pageId)).isEqualTo("mistral-small-latest");
    assertThat(translationService.bestEnglishByRecord(recordId)).containsEntry(1, "cheap");
    assertThat(translationService.modelBreakdown(recordId).get(0)[0])
        .isEqualTo("mistral-small-latest");
    translationService.refreshShown(pageId);
    assertThat(cached()).isEqualTo("cheap");
    jdbc.update("UPDATE page_text SET text_en = 'stale' WHERE page_id = ?", pageId);
    assertThat(translationService.refreshAllStale()).isEqualTo(1);
    assertThat(cached()).isEqualTo("cheap");
  }

  @Test
  void aPageWithOnlyACzechTranslationKeepsItsEnglishCache() {
    jdbc.update("UPDATE page_text SET text_en = 'kept' WHERE page_id = ?", pageId);
    czech("mistral-medium-latest", "Český překlad");

    translationService.refreshShown(pageId);
    assertThat(cached()).isEqualTo("kept");
    assertThat(translationService.refreshAllStale()).isZero();
    assertThat(cached()).isEqualTo("kept");
    assertThat(translationService.bestEnglish(pageId)).isNull();
  }

  @Test
  void existingRowsAreEnglishAndOneModelTranslatesAPageOncePerLanguage() {
    translation("mistral-small-latest", "cheap");
    assertThat(
            jdbc.queryForObject(
                "SELECT lang FROM page_translation WHERE page_id = ?", String.class, pageId))
        .isEqualTo("en");

    czech("mistral-small-latest", "levný");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM page_translation WHERE page_id = ?", Integer.class, pageId))
        .isEqualTo(2);
    assertThatThrownBy(() -> czech("mistral-small-latest", "again"))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO page_translation (page_id, lang, model, text)"
                        + " VALUES (?, 'fr', 'm', 'x')",
                    pageId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anExportIsEnglishUnlessToldOtherwiseAndOnlyInTheThreeLanguages() {
    jdbc.update(
        "INSERT INTO pdf_export (record_id, variant, page_ids, fingerprint)"
            + " VALUES (?, 'english', '{}', 'x')",
        recordId);
    assertThat(
            jdbc.queryForObject(
                "SELECT lang FROM pdf_export WHERE record_id = ?", String.class, recordId))
        .isEqualTo("en");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pdf_export (record_id, variant, page_ids, fingerprint, lang)"
                        + " VALUES (?, 'english', '{}', 'x', 'fr')",
                    recordId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
```

In `ManualCorrectionTest.java`, add after `anEnglishCorrectionReachesEveryCopyOfTheTranslation` (ends line 139):

```java
  @Test
  void anEnglishCorrectionLeavesOtherLanguagesAlone() throws Exception {
    jdbc.sql(
            "INSERT INTO page_translation (page_id, lang, model, text) VALUES (:p, 'cs', 'm', :c)")
        .param("p", page)
        .param("c", "Zemřel 17. ledna 1958. died on 17 January 1958")
        .update();

    ok(
        post(
            "/admin/pages/" + page + "/corrections",
            body("text_en", "died on 17 January 1958", "died on 17 January 1938", "year")));

    assertThat(text("SELECT text FROM page_translation WHERE page_id = ? AND lang = 'cs'", page))
        .isEqualTo("Zemřel 17. ledna 1958. died on 17 January 1958");
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*TranslationServiceTest' --tests '*ManualCorrectionTest'`
Expected: the five new tests FAIL with `PSQLException: ERROR: column "lang" of relation "page_translation" does not exist` (and `... of relation "pdf_export"`). The existing tests still pass.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V27__multilang_translation.sql`:

```sql
-- Translations into German and Czech are paid for once and kept beside the English ones, so a
-- later export of any variant or page selection reuses them. Existing rows are English.
ALTER TABLE page_translation RENAME COLUMN text_en TO text;

ALTER TABLE page_translation
    ADD COLUMN lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en', 'de', 'cs'));

ALTER TABLE page_translation DROP CONSTRAINT page_translation_page_id_model_key;
ALTER TABLE page_translation
    ADD CONSTRAINT page_translation_page_id_lang_model_key UNIQUE (page_id, lang, model);

-- For 'original' the language is irrelevant and stored as 'en'.
ALTER TABLE pdf_export
    ADD COLUMN lang text NOT NULL DEFAULT 'en' CHECK (lang IN ('en', 'de', 'cs'));
```

(`page_translation_page_id_model_key` is PostgreSQL's default name for V5's `UNIQUE (page_id, model)`. V5 ran through Flyway everywhere, so the name is the same in every database.)

- [ ] **Step 4: Entity and repository**

Replace the body of `PageTranslation.java` lines 15-43 with:

```java
@Table("page_translation")
public class PageTranslation {

  @Id private Long id;
  private Long pageId;
  private String lang;
  private String model;
  private String text;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getPageId() {
    return pageId;
  }

  public String getLang() {
    return lang;
  }

  public String getModel() {
    return model;
  }

  public String getText() {
    return text;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
```

Replace `PageTranslationRepository.java` lines 19-64 with:

```java
  @Modifying
  @Query(
      """
      INSERT INTO page_translation (page_id, lang, model, text, created_at)
      VALUES (:pageId, :lang, :model, :text, now())
      ON CONFLICT (page_id, lang, model) DO UPDATE
        SET text = EXCLUDED.text, created_at = now()
      """)
  void upsert(
      @Param("pageId") Long pageId,
      @Param("lang") String lang,
      @Param("model") String model,
      @Param("text") String text);

  /** Models that have already translated this page into English. */
  @Query("SELECT model FROM page_translation WHERE page_id = :pageId AND lang = 'en'")
  List<String> modelsFor(@Param("pageId") Long pageId);

  /**
   * Every English translation held for a page.
   *
   * <p>Returns the entity rather than a column projection: Spring Data JDBC maps a {@code
   * List<Map<String,Object>>} as a single-column result and fails with "expected 1, actual 3" the
   * moment a page actually has a translation.
   */
  @Query(
      "SELECT * FROM page_translation WHERE page_id = :pageId AND lang = 'en' ORDER BY model")
  List<PageTranslation> findByPageId(@Param("pageId") Long pageId);

  /** Pages of a record that no model has translated into English at the given quality yet. */
  @Query(
      """
      SELECT p.id FROM page p
      WHERE p.record_id = :recordId
        AND EXISTS (SELECT 1 FROM page_text pt WHERE pt.page_id = p.id
                      AND pt.text_raw IS NOT NULL AND pt.text_raw <> '')
        AND NOT EXISTS (SELECT 1 FROM page_translation tr
                          WHERE tr.page_id = p.id AND tr.lang = 'en' AND tr.model = :model)
      ORDER BY p.seq
      """)
  List<Long> pagesMissingModel(@Param("recordId") Long recordId, @Param("model") String model);

  /** How many pages of a record already have an English translation from this model. */
  @Query(
      """
      SELECT count(*) FROM page p
      JOIN page_translation tr ON tr.page_id = p.id AND tr.lang = 'en' AND tr.model = :model
      WHERE p.record_id = :recordId
      """)
  Integer countWithModel(@Param("recordId") Long recordId, @Param("model") String model);
```

(Keep the existing Javadoc above `upsert`, lines 13-18.)

- [ ] **Step 5: Pin TranslationService to English**

Run from the repo root:

```bash
sed -i '' \
  -e "s/tr\.text_en IS NOT NULL AND tr\.text_en <> ''/tr.lang = 'en' AND tr.text IS NOT NULL AND tr.text <> ''/" \
  -e "s/SELECT tr\.text_en FROM page_translation tr/SELECT tr.text FROM page_translation tr/" \
  -e "s/SELECT p\.seq, best\.text_en/SELECT p.seq, best.text/" \
  -e "s/EXISTS (SELECT 1 FROM page_translation tr WHERE tr\.page_id = ?)/EXISTS (SELECT 1 FROM page_translation tr WHERE tr.page_id = ? AND tr.lang = 'en')/" \
  backend/src/main/java/place/icomb/archiver/service/TranslationService.java
```

Then replace line 176:

```java
      translations.upsert(pageId, "en", model, Markdown.repairTables(textEn));
```

Check: `grep -n "text_en" backend/src/main/java/place/icomb/archiver/service/TranslationService.java` must show only lines 23, 182, 192, 215, 222, 226 and 227. Those are `page_text.text_en` and the `AS text_en` alias in `refreshAllStale`, and all of them stay.

- [ ] **Step 6: Manual corrections, viewer, fingerprint rename**

`ManualCorrectionService.java` lines 111-121 become:

```java
    } else if (whole) {
      jdbc.update(
          "UPDATE page_translation SET text = ? WHERE page_id = ? AND lang = 'en'",
          replace,
          pageId);
    } else {
      // Every model's translation of the page, so the cache cannot be refilled from a stale one.
      jdbc.update(
          "UPDATE page_translation SET text = replace(text, ?, ?)"
              + " WHERE page_id = ? AND lang = 'en' AND position(? in text) > 0",
          find,
          replace,
          pageId,
          find);
    }
```

`ViewerController.java` line 525 becomes:

```java
                        "chars", t.getText() == null ? 0 : t.getText().length()))
```

`PdfExportQueue.java` line 177: change `md5(t.text_en)` to `md5(t.text)`. Do nothing else to that file in this task.

- [ ] **Step 7: Rename the column in the existing tests**

```bash
cd backend/src/test/java && grep -rl "page_translation" . | xargs sed -i '' \
  -e 's/page_translation (page_id, model, text_en/page_translation (page_id, model, text/g' \
  -e 's/ON CONFLICT (page_id, model) DO UPDATE SET text_en = EXCLUDED.text_en/ON CONFLICT (page_id, lang, model) DO UPDATE SET text = EXCLUDED.text/g' \
  -e 's/UPDATE page_translation SET text_en/UPDATE page_translation SET text/g' \
  -e 's/SELECT text_en FROM page_translation/SELECT text FROM page_translation/g'
```

Check: `grep -rn "page_translation" backend/src/test | grep text_en` prints nothing.

- [ ] **Step 8: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL. The full suite runs, because every class that touches `page_translation` changed.

- [ ] **Step 9: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 10: Commit**

```bash
git add backend/src
git commit -m "Give page_translation a language, keep every existing reader on English"
```

---

## Task 2: Translator target language and the shared synchronous call

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/ai/Translator.java:6-30`
- Modify: `backend/src/main/java/place/icomb/archiver/ai/ChatTranslator.java:3-5,23-32,79-88,136-151`
- Modify: `backend/src/main/java/place/icomb/archiver/service/SyncTranslateWorker.java:86-108`
- Create: `backend/src/test/java/place/icomb/archiver/service/SyncTranslateWorkerTest.java`
- Test: `backend/src/test/java/place/icomb/archiver/ai/ChatTranslatorTest.java`

**Interfaces:**
- Consumes: `AiRegistry.Registration(String id, AiCapability capability, String provider, String model, String baseUrl, String endpointPath, String credentialEnv, int maxBatchSize, int rank, boolean enabled, JsonNode settings)`; `ResilientHttpClient.send(HttpRequest, BodyHandler)`.
- Produces:
  - `Map<String, Object> Translator.request(String text, String sourceLang, String targetLang)` (abstract)
  - `default Map<String, Object> Translator.request(String text, String sourceLang)` → `request(text, sourceLang, "en")`
  - `public static String SyncTranslateWorker.translate(ResilientHttpClient http, Translator translator, String apiKey, Map<String, Object> body) throws IOException, InterruptedException`. It throws `IllegalStateException("translate failed: HTTP <status> <first 300 chars>")` on a non-200 answer.

- [ ] **Step 1: Write the failing tests**

Add to `ChatTranslatorTest.java` these imports:

```java
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
```

and these tests:

```java
  private static ChatTranslator translator() {
    return new ChatTranslator(
        new AiRegistry.Registration(
            "t",
            AiCapability.TRANSLATION,
            "openai",
            "m",
            "http://localhost",
            "/v1/chat/completions",
            null,
            1,
            1,
            true,
            JsonNodeFactory.instance.objectNode()),
        "k");
  }

  private static String content(Map<String, Object> request) {
    return String.valueOf(((Map<?, ?>) ((List<?>) request.get("messages")).get(0)).get("content"));
  }

  @Test
  void theEnglishRequestIsExactlyWhatItWas() {
    String old =
        "Translate the following German or Czech archival document text into English. "
            + "Preserve all proper names, place names, dates, reference numbers and titles exactly "
            + "as written. Keep the markdown structure. Do not add commentary or explanation. "
            + "Output only the translation.\n\n";
    Map<String, Object> expected =
        Map.of(
            "max_tokens",
            4000,
            "temperature",
            0.1,
            "messages",
            List.of(Map.of("role", "user", "content", old + "Seite 1")));

    assertThat(translator().request("Seite 1", null)).isEqualTo(expected);
    assertThat(translator().request("Seite 1", "de", "en")).isEqualTo(expected);
  }

  @Test
  void aCzechOrGermanRequestNamesItsTargetAndKeepsTheEvidenceWording() {
    String czech = content(translator().request("Seite 1", "de", "cs"));
    assertThat(czech)
        .startsWith("Translate the following German or Czech archival document text into Czech. ")
        .contains("Preserve all proper names, place names, dates, reference numbers and titles")
        .endsWith("Output only the translation.\n\nSeite 1");
    assertThat(content(translator().request("Strana 1", "cs", "de")))
        .contains("text into German. ");
  }

  @Test
  void removesCzechAndGermanPreambles() {
    assertThat(ChatTranslator.stripPreamble("Here is the Czech translation:\n\nTajné"))
        .isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("### Czech translation\n\nTajné")).isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("Překlad:\n\nTajné")).isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("Übersetzung:\n\nGeheim")).isEqualTo("Geheim");
    assertThat(
            ChatTranslator.stripPreamble(
                "Here is the German translation of the document:\n\nGeheim"))
        .isEqualTo("Geheim");
  }

  @Test
  void keepsACzechHeadingThatIsPartOfTheDocument() {
    String real = "# Překlad smlouvy\n\nText";
    assertThat(ChatTranslator.stripPreamble(real)).isEqualTo(real);
  }
```

Create `backend/src/test/java/place/icomb/archiver/service/SyncTranslateWorkerTest.java`:

```java
package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.ChatTranslator;

class SyncTranslateWorkerTest {

  private static WireMockServer server;

  private final ResilientHttpClient http = ResilientHttpClient.builder().maxRetries(1).build();

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  private static ChatTranslator translator() {
    return new ChatTranslator(
        new AiRegistry.Registration(
            "t",
            AiCapability.TRANSLATION,
            "openai",
            "m-1",
            "http://localhost:" + server.port(),
            "/v1/chat/completions",
            null,
            1,
            1,
            true,
            JsonNodeFactory.instance.objectNode()),
        "k");
  }

  @Test
  void oneCallSendsTheBodyWithItsModelAndReturnsTheCleanedAnswer() throws Exception {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(
                okJson(
                    "{\"choices\":[{\"message\":{\"content\":"
                        + "\"Here is the Czech translation:\\n\\nTajné\"}}]}")));
    ChatTranslator t = translator();

    String out = SyncTranslateWorker.translate(http, t, "k", t.request("Geheim", "de", "cs"));

    assertThat(out).isEqualTo("Tajné");
    server.verify(
        postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer k"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("m-1")))
            .withRequestBody(containing("into Czech")));
  }

  @Test
  void aRefusalIsAnErrorCarryingTheStatusAndTheProvidersWords() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"quota exceeded\"}")));
    ChatTranslator t = translator();

    assertThatThrownBy(
            () -> SyncTranslateWorker.translate(http, t, "k", t.request("Geheim", null, "cs")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 400")
        .hasMessageContaining("quota exceeded");
  }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*ChatTranslatorTest' --tests '*SyncTranslateWorkerTest'`
Expected: compilation FAILS with `cannot find symbol: method request(String,String,String)` and `cannot find symbol: method translate(...)`.

- [ ] **Step 3: Translator**

Replace `Translator.java` lines 6-30 with:

```java
/**
 * Translates text, into English unless another language is asked for.
 *
 * <p>The request and the reading of its answer live together deliberately. They were previously
 * apart — one class built the batch line, another parsed it — and they drifted: the model wrapped
 * 13% of the archive in a code fence and opened 5,454 pages with "Here is the translation:",
 * neither of which the parser expected. A provider's quirks belong with the provider.
 */
public interface Translator extends AiImplementation {

  @Override
  default AiCapability capability() {
    return AiCapability.TRANSLATION;
  }

  /** Provider path a batch of these requests is submitted to, e.g. {@code /v1/chat/completions}. */
  String endpointPath();

  /** The request body for one item, translated into English. */
  default Map<String, Object> request(String text, String sourceLang) {
    return request(text, sourceLang, "en");
  }

  /**
   * The request body for one item.
   *
   * @param text the source text
   * @param sourceLang ISO 639-1 code, or null when unknown — the model handles any source
   * @param targetLang ISO 639-1 code of the language to translate into
   */
  Map<String, Object> request(String text, String sourceLang, String targetLang);
```

- [ ] **Step 4: ChatTranslator**

Add `import java.util.Locale;` after line 4.

Replace lines 23-32 (the `INSTRUCTION` constant and its Javadoc) with:

```java
  /**
   * Names, dates and reference numbers are evidence in these documents, so the instruction leads
   * with preserving them. A model that renders "Führungszeugnis" as "Leadership Certificate" —
   * which one candidate did — is worse than no translation, because it reads as authoritative.
   */
  private static String instruction(String targetLang) {
    return "Translate the following German or Czech archival document text into "
        + Locale.of(targetLang).getDisplayLanguage(Locale.ENGLISH)
        + ". Preserve all proper names, place names, dates, reference numbers and titles exactly "
        + "as written. Keep the markdown structure. Do not add commentary or explanation. "
        + "Output only the translation.\n\n";
  }

  private static final String LANGUAGE = "((?:english|czech|german)\\s+)?";
  private static final String TRANSLATION_WORD = "(?:translations?|překlad|[üÜ]bersetzung)";
```

Replace lines 79-88 (`request`) with:

```java
  @Override
  public Map<String, Object> request(String text, String sourceLang, String targetLang) {
    return Map.of(
        "max_tokens",
        4000,
        "temperature",
        0.1,
        "messages",
        List.of(Map.of("role", "user", "content", instruction(targetLang) + text)));
  }
```

In `stripPreamble`, replace lines 142-146 with:

```java
      boolean preamble =
          first.matches("(?i)^#{1,6}\\s*" + LANGUAGE + TRANSLATION_WORD + "\\s*[.:]?$")
              || first.matches("(?i)^\\*{0,2}" + LANGUAGE + TRANSLATION_WORD + "\\*{0,2}\\s*[.:]$")
              || first.matches(
                  "(?i)^(here is|here's|below is)\\s+the\\s+"
                      + LANGUAGE
                      + "translation\\b[^\\n]{0,200}:$");
```

(`[üÜ]` instead of `(?u)`, so the English patterns keep exactly the case folding they had.)

- [ ] **Step 5: SyncTranslateWorker**

Add `import java.io.IOException;` and add this method after `log()` (line 68):

```java
  public static String translate(
      ResilientHttpClient http, Translator translator, String apiKey, Map<String, Object> body)
      throws IOException, InterruptedException {
    Map<String, Object> withModel = new java.util.LinkedHashMap<>(body);
    withModel.put("model", translator.model());

    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(translator.endpoint() + translator.endpointPath()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(withModel)))
            .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "translate failed: HTTP "
              + response.statusCode()
              + " "
              + response.body().substring(0, Math.min(300, response.body().length())));
    }
    return translator.parse(MAPPER.readTree(response.body()));
  }
```

Replace lines 86-108 of `processJob` (from `Map<String, Object> body = ...` through `String translated = translator.parse(parsed);`) with:

```java
    String translated = translate(http, translator, apiKey, translator.request(text, null));
```

Remove the now-unused `import com.fasterxml.jackson.databind.JsonNode;` if Spotless or the compiler flags it.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*ChatTranslatorTest' --tests '*SyncTranslateWorkerTest' --tests '*ProviderApiTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "Translator takes a target language; share the synchronous translate call"
```

---

## Task 3: TranslationService per-language reads, writes and on-demand translation

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/TranslationService.java:3-10,32-46,94-114,137-160` and append methods after `refreshAllStale` (line 230)
- Create: `backend/src/test/java/place/icomb/archiver/TranslatorStub.java`
- Create: `backend/src/test/java/place/icomb/archiver/service/TranslateMissingTest.java`
- Test: `backend/src/test/java/place/icomb/archiver/service/TranslationServiceTest.java`

**Interfaces:**
- Consumes: `PageTranslationRepository.upsert(Long, String, String, String)` (Task 1); `Translator.request(String, String, String)` and `SyncTranslateWorker.translate(...)` (Task 2); `AiRegistry.best(AiCapability)`, `AiRegistry.credential(Registration)`; `CoverSheetService.languageName(String)` (package-private static, same package); `Markdown.repairTables(String)`.
- Produces:
  - `public Map<Integer, String> bestByRecord(Long recordId, String lang)`. `bestEnglishByRecord(recordId)` now delegates with `"en"`.
  - `public List<String[]> modelBreakdown(Long recordId, String lang)`. `modelBreakdown(recordId)` now delegates with `"en"`.
  - `public void recordIn(Long pageId, String lang, String model, String text)`: `en` goes through `record(...)`; any other language is upserted after table repair, the English cache is not touched, and blank text is not stored.
  - `public int translateMissing(long recordId, List<Long> pageIds, String lang) throws IOException, InterruptedException`. It returns how many pages were sent. It returns 0 for `en` and for the record's own language. Otherwise it throws `IllegalStateException("No translation model is configured, so the <Language> text cannot be made")` when no translator is configured, and `IllegalStateException("Could not translate page <seq> into <Language>: <cause>")` on a failed call. Pages stored before a failure stay stored.
  - Test helper `place.icomb.archiver.TranslatorStub`: `MODEL`, `KEY_ENV`, `PATH`, `register(JdbcTemplate, WireMockServer)`, `unregister(JdbcTemplate)`, `reply(WireMockServer, String)`, `body(String)`, `calls(WireMockServer)`.

- [ ] **Step 1: Write the test helper and the failing tests**

Create `backend/src/test/java/place/icomb/archiver/TranslatorStub.java`:

```java
package place.icomb.archiver;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** The one TRANSLATION model a test may call: a WireMock chat endpoint, ranked first. */
public final class TranslatorStub {

  public static final String MODEL = "stub-translator";
  public static final String KEY_ENV = "STUB_TRANSLATE_KEY";
  public static final String PATH = "/v1/chat/completions";

  private TranslatorStub() {}

  public static void register(JdbcTemplate jdbc, WireMockServer server) {
    unregister(jdbc);
    jdbc.update("UPDATE ai_implementation SET enabled = false WHERE capability = 'TRANSLATION'");
    jdbc.update(
        """
        INSERT INTO ai_implementation
            (id, capability, provider, model, base_url, endpoint_path, credential_env,
             max_batch_size, rank, enabled, settings)
        VALUES ('stub:translator', 'TRANSLATION', 'openai', ?, ?, ?, ?, 1, 0, true, '{}')
        """,
        MODEL,
        "http://localhost:" + server.port(),
        PATH,
        KEY_ENV);
  }

  public static void unregister(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM ai_implementation WHERE id = 'stub:translator'");
  }

  public static void reply(WireMockServer server, String content) {
    server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body(content))));
  }

  public static String body(String content) {
    try {
      return new ObjectMapper()
          .writeValueAsString(
              Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int calls(WireMockServer server) {
    return server.findAll(postRequestedFor(urlEqualTo(PATH))).size();
  }
}
```

Create `backend/src/test/java/place/icomb/archiver/service/TranslateMissingTest.java`:

```java
package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.TranslatorStub;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class TranslateMissingTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  static WireMockServer model =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    model.start();
  }

  @AfterAll
  static void stop() {
    model.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(TranslatorStub.KEY_ENV, () -> "stub-key");
  }

  @Autowired private TranslationService translations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    model.resetAll();
    TranslatorStub.register(jdbc, model);
    TranslatorStub.reply(model, "Překlad:\n\nČeský text");
    jdbc.execute("DELETE FROM record");
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private String czech(long pageId) {
    return jdbc
        .queryForList(
            "SELECT text FROM page_translation WHERE page_id = ? AND lang = 'cs'",
            String.class,
            pageId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Test
  void eachMissingPageIsTranslatedOnceAndThenNeverAgain() throws Exception {
    assertThat(translations.translateMissing(record, pages, "cs")).isEqualTo(3);
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
    assertThat(czech(pages.get(0))).isEqualTo("Český text");

    assertThat(translations.translateMissing(record, pages, "cs")).isZero();
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }

  @Test
  void onlyTheSelectedPagesAreTranslated() throws Exception {
    assertThat(translations.translateMissing(record, pages.subList(1, 2), "cs")).isEqualTo(1);
    assertThat(czech(pages.get(0))).isNull();
    assertThat(czech(pages.get(1))).isEqualTo("Český text");
    assertThat(czech(pages.get(2))).isNull();
  }

  @Test
  void theRecordsOwnLanguageAndEnglishAreNeverTranslated() throws Exception {
    assertThat(translations.translateMissing(record, pages, "de")).isZero();
    assertThat(translations.translateMissing(record, pages, "en")).isZero();
    assertThat(TranslatorStub.calls(model)).isZero();
  }

  @Test
  void aBlankPageIsNotSentToTheModel() throws Exception {
    jdbc.update("UPDATE page_text SET text_raw = '   ' WHERE page_id = ?", pages.get(1));
    assertThat(translations.translateMissing(record, pages, "cs")).isEqualTo(2);
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
    assertThat(czech(pages.get(1))).isNull();
  }

  @Test
  void theRequestAsksForCzechWithTheConfiguredKey() throws Exception {
    translations.translateMissing(record, pages.subList(0, 1), "cs");
    model.verify(
        postRequestedFor(urlEqualTo(TranslatorStub.PATH))
            .withHeader("Authorization", equalTo("Bearer stub-key"))
            .withRequestBody(containing("into Czech"))
            .withRequestBody(containing("Text 1")));
    assertThat(
            jdbc.queryForObject(
                "SELECT model FROM page_translation WHERE page_id = ? AND lang = 'cs'",
                String.class,
                pages.get(0)))
        .isEqualTo(TranslatorStub.MODEL);
  }

  @Test
  void theEnglishCacheAndRankingAreUntouched() throws Exception {
    String cached =
        jdbc.queryForObject(
            "SELECT text_en FROM page_text WHERE page_id = ?", String.class, pages.get(0));
    translations.translateMissing(record, pages, "cs");
    assertThat(
            jdbc.queryForObject(
                "SELECT text_en FROM page_text WHERE page_id = ?", String.class, pages.get(0)))
        .isEqualTo(cached);
    assertThat(translations.bestEnglish(pages.get(0))).isEqualTo("One");
  }

  @Test
  void aFailedCallStopsWithAReadableErrorAndKeepsWhatWasStored() {
    model.resetAll();
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson(TranslatorStub.body("Český text")))
            .willSetStateTo("exhausted"));
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs("exhausted")
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"quota exceeded\"}")));

    assertThatThrownBy(() -> translations.translateMissing(record, pages, "cs"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("page 2")
        .hasMessageContaining("Czech")
        .hasMessageContaining("HTTP 400");
    assertThat(czech(pages.get(0))).isEqualTo("Český text");
    assertThat(czech(pages.get(1))).isNull();
  }

  @Test
  void withNoTranslatorConfiguredItSaysSo() {
    TranslatorStub.unregister(jdbc);
    assertThatThrownBy(() -> translations.translateMissing(record, pages, "cs"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No translation model is configured")
        .hasMessageContaining("Czech");
    assertThat(TranslatorStub.calls(model)).isZero();
  }
}
```

Add to `TranslationServiceTest.java`, after the Task 1 tests:

```java
  @Test
  void czechIsRankedAmongCzechTranslations() {
    czech("mistral-small-latest", "levný");
    czech("mistral-medium-latest", "pečlivý");
    translation("google/gemma-4-31B-it", "english");

    assertThat(translationService.bestByRecord(recordId, "cs")).containsEntry(1, "pečlivý");
    assertThat(translationService.modelBreakdown(recordId, "cs").get(0)[0])
        .isEqualTo("mistral-medium-latest");
    assertThat(translationService.bestByRecord(recordId, "de")).isEmpty();
  }

  @Test
  void recordingACzechTranslationLeavesTheEnglishCacheAlone() {
    jdbc.update("UPDATE page_text SET text_en = 'kept' WHERE page_id = ?", pageId);
    translationService.recordIn(pageId, "cs", "mistral-medium-latest", "Český");
    translationService.recordIn(pageId, "cs", "mistral-small-latest", "   ");

    assertThat(cached()).isEqualTo("kept");
    assertThat(translationService.bestByRecord(recordId, "cs")).containsEntry(1, "Český");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM page_translation WHERE page_id = ? AND lang = 'cs'",
                Integer.class,
                pageId))
        .isEqualTo(1);
  }

  @Test
  void aCzechTableIsRepairedOnTheWayIn() {
    String table = "| Jméno | Datum |\n| --- |\n| Czernin | 1943 |";
    translationService.recordIn(pageId, "cs", "mistral-medium-latest", table);
    assertThat(translationService.bestByRecord(recordId, "cs"))
        .containsEntry(1, Markdown.repairTables(table));
  }

  @Test
  void englishRecordedThroughRecordInStillRefreshesTheCache() {
    translationService.recordIn(pageId, "en", "mistral-small-latest", "cheap");
    assertThat(cached()).isEqualTo("cheap");
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*TranslationServiceTest' --tests '*TranslateMissingTest'`
Expected: compilation FAILS with `cannot find symbol: method bestByRecord(Long,String)`, `recordIn`, `translateMissing`.

- [ ] **Step 3: Implement**

In `TranslationService.java`, replace the imports (lines 3-10) with:

```java
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.ChatTranslator;
import place.icomb.archiver.repository.PageTranslationRepository;
import place.icomb.archiver.repository.RecordTranslationRepository;
```

After the `registry` field (line 35), add:

```java
  private final ResilientHttpClient http = ResilientHttpClient.builder().build();

  /**
   * One lock per record and language, held while the missing pages are found and translated, so
   * two exports building at once never pay for the same page. Assumes one backend instance, as
   * PdfExportWorker's restart recovery already does.
   */
  private final ConcurrentHashMap<String, Object> translating = new ConcurrentHashMap<>();
```

Replace `bestEnglishByRecord` (lines 94-114, keeping its Javadoc above it) with:

```java
  public Map<Integer, String> bestEnglishByRecord(Long recordId) {
    return bestByRecord(recordId, "en");
  }

  public Map<Integer, String> bestByRecord(Long recordId, String lang) {
    Map<Integer, String> out = new HashMap<>();
    jdbc.query(
        """
        SELECT p.seq, best.text
        FROM page p
        JOIN LATERAL (
            SELECT tr.text FROM page_translation tr
            WHERE tr.page_id = p.id AND tr.lang = ? AND tr.text IS NOT NULL AND tr.text <> ''
            ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
            LIMIT 1
        ) best ON true
        WHERE p.record_id = ?
        """,
        rs -> {
          out.put(rs.getInt(1), rs.getString(2));
        },
        lang,
        ranks(),
        recordId);
    return out;
  }
```

Replace `modelBreakdown` (lines 137-160, keeping its Javadoc) with:

```java
  public List<String[]> modelBreakdown(Long recordId) {
    return modelBreakdown(recordId, "en");
  }

  public List<String[]> modelBreakdown(Long recordId, String lang) {
    List<String[]> out = new ArrayList<>();
    jdbc.query(
        """
        SELECT shown.model, count(*) AS pages
        FROM page p
        JOIN LATERAL (
            SELECT tr.model FROM page_translation tr
            WHERE tr.page_id = p.id AND tr.lang = ? AND tr.text IS NOT NULL AND tr.text <> ''
            ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
            LIMIT 1
        ) shown ON true
        WHERE p.record_id = ?
        GROUP BY shown.model
        ORDER BY COALESCE(array_position(?::text[], shown.model), 999)
        """,
        rs -> {
          out.add(new String[] {rs.getString(1), String.valueOf(rs.getInt(2))});
        },
        lang,
        ranks(),
        recordId,
        ranks());
    return out;
  }
```

After `refreshAllStale()` (ends line 230), add:

```java
  /**
   * Records a model's translation of a page into {@code lang}.
   *
   * <p>English goes through {@link #record}, which also repoints the cache. Other languages have no
   * cache: page_text.text_en is English only.
   */
  public void recordIn(Long pageId, String lang, String model, String text) {
    if ("en".equals(lang)) {
      record(pageId, model, text);
      return;
    }
    if (text != null && !text.isBlank()) {
      translations.upsert(pageId, lang, model, Markdown.repairTables(text));
    }
  }

  /**
   * Translates the selected pages that have no translation into {@code lang} yet, one request per
   * page, storing each as it arrives so a failure part-way keeps what was already paid for.
   *
   * <p>English is never translated here, and neither is the record's own language: its text is
   * the transcription.
   *
   * @return how many pages were sent to the model
   */
  public int translateMissing(long recordId, List<Long> pageIds, String lang)
      throws IOException, InterruptedException {
    if ("en".equals(lang)) {
      return 0;
    }
    String recordLang =
        jdbc.queryForObject("SELECT lang FROM record WHERE id = ?", String.class, recordId);
    if (lang.equals(recordLang)) {
      return 0;
    }
    synchronized (translating.computeIfAbsent(recordId + ":" + lang, k -> new Object())) {
      List<Map<String, Object>> missing =
          jdbc
              .queryForList(
                  """
                  SELECT p.id, p.seq, pt.text_raw
                  FROM unnest(string_to_array(?, ',')::bigint[]) WITH ORDINALITY AS sel(page_id, ord)
                  JOIN page p ON p.id = sel.page_id
                  JOIN page_text pt ON pt.page_id = p.id
                  WHERE NOT EXISTS (SELECT 1 FROM page_translation tr
                                    WHERE tr.page_id = p.id AND tr.lang = ? AND tr.text <> '')
                  ORDER BY sel.ord
                  """,
                  pageIds.stream().map(String::valueOf).collect(Collectors.joining(",")),
                  lang)
              .stream()
              .filter(row -> row.get("text_raw") instanceof String text && !text.isBlank())
              .toList();
      if (missing.isEmpty()) {
        return 0;
      }
      String language = CoverSheetService.languageName(lang);
      AiRegistry.Registration registration =
          registry
              .best(AiCapability.TRANSLATION)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No translation model is configured, so the "
                              + language
                              + " text cannot be made"));
      String apiKey = registry.credential(registration);
      ChatTranslator translator = new ChatTranslator(registration, apiKey);
      for (Map<String, Object> page : missing) {
        String translated;
        try {
          translated =
              SyncTranslateWorker.translate(
                  http,
                  translator,
                  apiKey,
                  translator.request((String) page.get("text_raw"), recordLang, lang));
        } catch (IOException | RuntimeException e) {
          throw new IllegalStateException(
              "Could not translate page "
                  + page.get("seq")
                  + " into "
                  + language
                  + ": "
                  + e.getMessage(),
              e);
        }
        recordIn(((Number) page.get("id")).longValue(), lang, translator.model(), translated);
      }
      return missing.size();
    }
  }
```

The existing `place.icomb.archiver.ai.AiRegistry` field and constructor parameter may now be written as `AiRegistry`. Spotless leaves either form alone.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*TranslationServiceTest' --tests '*TranslateMissingTest' --tests '*PdfExportServiceTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "Translate a page into Czech or German once, on demand, and keep it"
```

---

## Task 4: Export queue: lang, translation variant, fingerprint, reuse, names

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java:90-97,122` (enum rename)
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java:29-65,75-118,150-189,214-251,271-290,301-325` and add `refingerprint` after `markFailed` (line 351)
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java:80`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java:102` (two-argument `fileSuffix`; the rest is Task 7)
- Modify: `backend/src/test/java/place/icomb/archiver/service/PdfExportWorkerFailureTest.java:46-50`
- Test: `backend/src/test/java/place/icomb/archiver/service/PdfExportQueueTest.java`

**Interfaces:**
- Consumes: DB `pdf_export.lang`, `page_translation.lang/text` (Task 1).
- Produces:
  - `PdfExportService.Variant { ORIGINAL, TRANSLATION, SIDE_BY_SIDE }`
  - `public static final List<String> PdfExportQueue.LANGS = List.of("en", "de", "cs")`
  - `public static String parseLang(String s)`: blank gives `"en"`; anything else not in `LANGS` throws `IllegalArgumentException("Unknown language: <s> (accepted: en, de, cs)")`.
  - `public static String resolveLang(String variant, String lang)` → `parseLang(lang)`. It refuses `english` with a non-`en` lang.
  - `public static Variant parseVariant(String s)`: also `translation`; `english` means `TRANSLATION`.
  - `static String apiName(Variant v, String lang)`; `public static String fileSuffix(Variant v, String lang)`
  - `public String fingerprint(long recordId, List<Long> pageIds)` (= `"en"`), `fingerprint(long, List<Long>, String lang)`, `fingerprint(long, List<Long>, String lang, Instant before)`
  - `public Requested request(long recordId, Variant variant, List<Long> pageIds)` (= `"en"`), `request(long, Variant, List<Long>, String lang)`. `ORIGINAL` is stored as `en`.
  - `public void refingerprint(String id, String fingerprint)`: updates only a `building` row.
  - `record View(String id, String state, int pageCount, String variant, String lang, Long bytes, Instant expiresAt, String error)`
  - `record Claimed(String id, long recordId, Variant variant, String lang, List<Long> pageIds, String fingerprint, Instant requestedAt)`
  - `record Row(String id, long recordId, String state, String path, Variant variant, String lang, String error)`

- [ ] **Step 1: Write the failing tests**

Add to `PdfExportQueueTest.java` (it already imports `assertThat`, `assertThatThrownBy`, `List`, `Variant`):

```java
  // --- languages ---------------------------------------------------------------------------

  @Test
  void theLanguagesAnExportCanBeMadeInAreParsedAndAnythingElseIsRefused() {
    assertThat(PdfExportQueue.parseLang(null)).isEqualTo("en");
    assertThat(PdfExportQueue.parseLang(" ")).isEqualTo("en");
    assertThat(PdfExportQueue.parseLang("CS")).isEqualTo("cs");
    assertThat(PdfExportQueue.parseLang("de")).isEqualTo("de");
    assertThatThrownBy(() -> PdfExportQueue.parseLang("fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fr")
        .hasMessageContaining("en, de, cs");
  }

  @Test
  void translationIsTheVariantsNameAndEnglishItsEnglishAlias() {
    assertThat(PdfExportQueue.parseVariant("translation")).isEqualTo(Variant.TRANSLATION);
    assertThat(PdfExportQueue.parseVariant("english")).isEqualTo(Variant.TRANSLATION);
    assertThat(PdfExportQueue.resolveLang("english", null)).isEqualTo("en");
    assertThat(PdfExportQueue.resolveLang("translation", "cs")).isEqualTo("cs");
    assertThat(PdfExportQueue.resolveLang(null, "de")).isEqualTo("de");
    assertThatThrownBy(() -> PdfExportQueue.resolveLang("english", "cs"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("translation");
  }

  @Test
  void filesAreNamedForTheirLanguage() {
    assertThat(PdfExportQueue.fileSuffix(Variant.ORIGINAL, "en")).isEqualTo("-original");
    assertThat(PdfExportQueue.fileSuffix(Variant.TRANSLATION, "en")).isEqualTo("-english");
    assertThat(PdfExportQueue.fileSuffix(Variant.SIDE_BY_SIDE, "en"))
        .isEqualTo("-original-and-english");
    assertThat(PdfExportQueue.fileSuffix(Variant.TRANSLATION, "cs")).isEqualTo("-czech");
    assertThat(PdfExportQueue.fileSuffix(Variant.SIDE_BY_SIDE, "cs"))
        .isEqualTo("-original-and-czech");
    assertThat(PdfExportQueue.fileSuffix(Variant.TRANSLATION, "de")).isEqualTo("-german");
    assertThat(PdfExportQueue.fileSuffix(Variant.SIDE_BY_SIDE, "de"))
        .isEqualTo("-original-and-german");
  }

  @Test
  void aCzechTranslationChangesTheCzechFingerprintButNotTheEnglishOne() {
    String english = queue.fingerprint(record, pages);
    String czech = queue.fingerprint(record, pages, "cs");
    jdbc.update(
        "INSERT INTO page_translation (page_id, lang, model, text) VALUES (?, 'cs', 'better', 'x')",
        pages.get(0));

    assertThat(queue.fingerprint(record, pages, "cs")).isNotEqualTo(czech);
    assertThat(queue.fingerprint(record, pages)).isEqualTo(english);
    assertThat(queue.fingerprint(record, pages, "en")).isEqualTo(english);
  }

  @Test
  void eachLanguageHasItsOwnFingerprint() {
    assertThat(
            List.of(
                queue.fingerprint(record, pages, "en"),
                queue.fingerprint(record, pages, "de"),
                queue.fingerprint(record, pages, "cs")))
        .doesNotHaveDuplicates();
  }

  @Test
  void correctingTheTranscriptionChangesAGermanFingerprint() {
    String before = queue.fingerprint(record, pages, "de");
    jdbc.update("UPDATE page_text SET text_raw = 'Korrigiert' WHERE page_id = ?", pages.get(0));
    assertThat(queue.fingerprint(record, pages, "de")).isNotEqualTo(before);
  }

  @Test
  void aTranslationMadeAfterTheRequestIsNotAChangeToThePages() {
    queue.request(record, Variant.TRANSLATION, pages, "cs");
    PdfExportQueue.Claimed claimed = queue.claimNext().orElseThrow();
    jdbc.update(
        "INSERT INTO page_translation (page_id, lang, model, text) VALUES (?, 'cs', 'm', 'x')",
        pages.get(0));

    assertThat(queue.fingerprint(record, pages, "cs", claimed.requestedAt()))
        .isEqualTo(claimed.fingerprint());
    assertThat(queue.fingerprint(record, pages, "cs")).isNotEqualTo(claimed.fingerprint());
  }

  @Test
  void reuseMatchesTheLanguage() {
    String cs = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    String de = queue.request(record, Variant.TRANSLATION, pages, "de").view().id();
    String en = queue.request(record, Variant.TRANSLATION, pages).view().id();

    assertThat(List.of(cs, de, en)).doesNotHaveDuplicates();
    assertThat(queue.request(record, Variant.TRANSLATION, pages, "cs").view().id()).isEqualTo(cs);
  }

  @Test
  void anExportCarriesItsLanguageAndAnEnglishOneKeepsItsName() {
    PdfExportQueue.View cs = queue.request(record, Variant.TRANSLATION, pages, "cs").view();
    assertThat(cs.lang()).isEqualTo("cs");
    assertThat(cs.variant()).isEqualTo("translation");
    assertThat(queue.row(cs.id()).orElseThrow().lang()).isEqualTo("cs");

    PdfExportQueue.View en = queue.request(record, Variant.TRANSLATION, pages).view();
    assertThat(en.lang()).isEqualTo("en");
    assertThat(en.variant()).isEqualTo("english");
  }

  @Test
  void anOriginalIsTheSameExportWhateverLanguageIsAsked() {
    PdfExportQueue.View asked = queue.request(record, Variant.ORIGINAL, pages, "cs").view();
    assertThat(asked.lang()).isEqualTo("en");
    assertThat(request().view().id()).isEqualTo(asked.id());
  }

  @Test
  void aClaimCarriesTheLanguageAndWhenItWasAskedFor() {
    queue.request(record, Variant.SIDE_BY_SIDE, pages, "de");
    PdfExportQueue.Claimed claimed = queue.claimNext().orElseThrow();
    assertThat(claimed.lang()).isEqualTo("de");
    assertThat(claimed.requestedAt()).isNotNull();
  }

  private String storedFingerprint(String id) {
    return jdbc.queryForObject(
        "SELECT fingerprint FROM pdf_export WHERE id = ?::uuid", String.class, id);
  }

  @Test
  void refingerprintingTouchesOnlyABuildingExport() {
    String id = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    queue.refingerprint(id, "while-queued");
    assertThat(storedFingerprint(id)).isNotEqualTo("while-queued");

    queue.claimNext();
    queue.refingerprint(id, "while-building");
    assertThat(storedFingerprint(id)).isEqualTo("while-building");
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportQueueTest'`
Expected: compilation FAILS with `cannot find symbol: method parseLang(String)`, `variable TRANSLATION`, `method lang()`.

- [ ] **Step 3: Rename the variant**

```bash
cd backend/src && grep -rl "Variant.ENGLISH" main test | xargs sed -i '' 's/Variant\.ENGLISH/Variant.TRANSLATION/g'
```

In `PdfExportService.java`, replace lines 93-94 with:

```java
    /** The text of each page, rendered from its markdown: English unless asked otherwise. */
    TRANSLATION,
```

and on line 122 change `case ENGLISH ->` to `case TRANSLATION ->`.

- [ ] **Step 4: PdfExportQueue**

Replace lines 29-65 (records, `VIEW_SQL`, `VIEW`) with:

```java
  /** What a caller sees of an export. */
  public record View(
      String id,
      String state,
      int pageCount,
      String variant,
      String lang,
      Long bytes,
      Instant expiresAt,
      String error) {}

  /** A request's outcome: the export, and whether this call created it. */
  public record Requested(View view, boolean created) {}

  /** An export a worker has taken. */
  public record Claimed(
      String id,
      long recordId,
      Variant variant,
      String lang,
      List<Long> pageIds,
      String fingerprint,
      Instant requestedAt) {}

  /** The columns the file endpoint needs. */
  public record Row(
      String id,
      long recordId,
      String state,
      String path,
      Variant variant,
      String lang,
      String error) {}

  public static final List<String> LANGS = List.of("en", "de", "cs");

  private static final Timestamp NO_CUTOFF =
      Timestamp.from(Instant.parse("9999-12-31T00:00:00Z"));

  private static final String VIEW_SQL =
      "SELECT id::text AS id, state, cardinality(page_ids) AS page_count, variant, lang, bytes,"
          + " expires_at, error FROM pdf_export";

  private static final RowMapper<View> VIEW =
      (rs, n) -> {
        Timestamp expires = rs.getTimestamp("expires_at");
        String lang = rs.getString("lang");
        return new View(
            rs.getString("id"),
            rs.getString("state"),
            rs.getInt("page_count"),
            apiName(fromDb(rs.getString("variant")), lang),
            lang,
            (Long) rs.getObject("bytes"),
            expires == null ? null : expires.toInstant(),
            rs.getString("error"));
      };
```

Replace lines 75-118 (variants section) with:

```java
  // --- variants and languages --------------------------------------------------------------

  static String dbName(Variant v) {
    return switch (v) {
      case ORIGINAL -> "original";
      case TRANSLATION -> "english";
      case SIDE_BY_SIDE -> "side_by_side";
    };
  }

  static Variant fromDb(String s) {
    return switch (s) {
      case "english" -> Variant.TRANSLATION;
      case "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> Variant.ORIGINAL;
    };
  }

  /** The API's spelling: an English translation still answers to english, as it always has. */
  static String apiName(Variant v, String lang) {
    if (v == Variant.TRANSLATION && !"en".equals(lang)) {
      return "translation";
    }
    return v == Variant.SIDE_BY_SIDE ? "side-by-side" : dbName(v);
  }

  /** Reads the API's spelling; nothing given means the scans. */
  public static Variant parseVariant(String s) {
    if (s == null || s.isBlank()) {
      return Variant.ORIGINAL;
    }
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "original" -> Variant.ORIGINAL;
      case "translation", "english" -> Variant.TRANSLATION;
      case "side-by-side", "sidebyside", "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> throw new IllegalArgumentException("Unknown variant: " + s);
    };
  }

  public static String parseLang(String s) {
    if (s == null || s.isBlank()) {
      return "en";
    }
    String lang = s.trim().toLowerCase(Locale.ROOT);
    if (!LANGS.contains(lang)) {
      throw new IllegalArgumentException(
          "Unknown language: " + s + " (accepted: " + String.join(", ", LANGS) + ")");
    }
    return lang;
  }

  public static String resolveLang(String variant, String lang) {
    String parsed = parseLang(lang);
    if (variant != null
        && "english".equals(variant.trim().toLowerCase(Locale.ROOT))
        && !"en".equals(parsed)) {
      throw new IllegalArgumentException(
          "variant 'english' is the English translation; ask for variant 'translation' with lang "
              + parsed);
    }
    return parsed;
  }

  /** Named for what the file contains, so a folder of exports reads without opening them. */
  public static String fileSuffix(Variant v, String lang) {
    String language =
        switch (lang) {
          case "de" -> "german";
          case "cs" -> "czech";
          default -> "english";
        };
    return switch (v) {
      case ORIGINAL -> "-original";
      case TRANSLATION -> "-" + language;
      case SIDE_BY_SIDE -> "-original-and-" + language;
    };
  }
```

Replace `fingerprint` (lines 150-189, keeping and extending its Javadoc) with:

```java
  public String fingerprint(long recordId, List<Long> pageIds) {
    return fingerprint(recordId, pageIds, "en");
  }

  public String fingerprint(long recordId, List<Long> pageIds, String lang) {
    return fingerprint(recordId, pageIds, lang, null);
  }

  /**
   * What these pages are right now, for an export in {@code lang}: an md5 over the whole record and
   * archive rows (minus the volatile {@code updated_at}), the ids and engines of every
   * transcription and the ids and models of every translation into {@code lang} in the record (the
   * cover sheet prints record-wide provenance), and, for each selected page in order, its id,
   * record, position, scan, text, a hash of its English text, and each of its translations into
   * {@code lang} (id and a hash of its text, since a re-run overwrites a row in place). Outside
   * English it also covers the language and the transcriptions, which a German export of a German
   * record shows. Re-OCR, a new translation, a replaced scan or a moved page all change it.
   *
   * @param before when given, translations into a language other than English made after it are
   *     left out: those are the ones exports made themselves while building, not a change to the
   *     pages
   */
  public String fingerprint(long recordId, List<Long> pageIds, String lang, Instant before) {
    Timestamp cutoff = before == null ? NO_CUTOFF : Timestamp.from(before);
    return jdbc.queryForObject(
        """
        SELECT md5(
          coalesce((SELECT md5((to_jsonb(r) - 'updated_at')::text || to_jsonb(a)::text)
                    FROM record r JOIN archive a ON a.id = r.archive_id
                    WHERE r.id = ?), '')
          || coalesce((SELECT md5(string_agg(pt2.id::text || ':' || pt2.engine, ','
                                             ORDER BY pt2.id))
                       FROM page_text pt2 JOIN page p2 ON p2.id = pt2.page_id
                       WHERE p2.record_id = ?), '')
          || coalesce((SELECT md5(string_agg(t2.id::text || ':' || t2.model, ','
                                             ORDER BY t2.id))
                       FROM page_translation t2 JOIN page p3 ON p3.id = t2.page_id
                       WHERE p3.record_id = ? AND t2.lang = ?
                         AND (t2.lang = 'en' OR t2.created_at <= ?)), '')
          || coalesce(string_agg(
               concat_ws(':', p.id, p.record_id, p.seq, p.attachment_id,
                         coalesce(pt.id::text, '-'),
                         md5(coalesce(pt.text_en, '')),
                         coalesce((SELECT string_agg(t.id::text || '=' || md5(t.text), ',' ORDER BY t.id)
                                   FROM page_translation t
                                   WHERE t.page_id = p.id AND t.lang = ?
                                     AND (t.lang = 'en' OR t.created_at <= ?)), '-')),
               '|' ORDER BY sel.ord), '')
          || CASE WHEN ? = 'en' THEN ''
                  ELSE ? || md5(coalesce(string_agg(coalesce(pt.text_raw, ''), '|'
                                                    ORDER BY sel.ord), ''))
             END)
        FROM unnest(string_to_array(?, ',')::bigint[]) WITH ORDINALITY AS sel(page_id, ord)
        JOIN page p ON p.id = sel.page_id
        LEFT JOIN page_text pt ON pt.page_id = p.id
        """,
        String.class,
        recordId,
        recordId,
        recordId,
        lang,
        cutoff,
        lang,
        cutoff,
        lang,
        lang,
        csv(pageIds));
  }
```

Replace `request` (lines 214-251, keeping its Javadoc and adding "and language" to "the same record, variant") with:

```java
  @Transactional
  public Requested request(long recordId, Variant variant, List<Long> pageIds) {
    return request(recordId, variant, pageIds, "en");
  }

  @Transactional
  public Requested request(long recordId, Variant variant, List<Long> pageIds, String lang) {
    String stored = variant == Variant.ORIGINAL ? "en" : lang;
    String fingerprint = fingerprint(recordId, pageIds, stored);
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", fingerprint);

    List<View> reusable =
        jdbc.query(
            VIEW_SQL
                + " WHERE record_id = ? AND variant = ? AND lang = ? AND fingerprint = ?"
                + " AND (state IN ('queued', 'building')"
                + "      OR (state = 'ready' AND expires_at > now()))"
                + " ORDER BY created_at DESC LIMIT 1",
            VIEW,
            recordId,
            dbName(variant),
            stored,
            fingerprint);
    if (!reusable.isEmpty()) {
      return new Requested(reusable.get(0), false);
    }

    String id =
        jdbc.queryForObject(
            "INSERT INTO pdf_export (record_id, variant, lang, page_ids, fingerprint)"
                + " VALUES (?, ?, ?, string_to_array(?, ',')::bigint[], ?) RETURNING id::text",
            String.class,
            recordId,
            dbName(variant),
            stored,
            csv(pageIds),
            fingerprint);
    return new Requested(find(id).orElseThrow(), true);
  }
```

In `row` (lines 271-290), change the SQL to `"SELECT id::text AS id, record_id, state, path, variant, lang, error"` and the mapper to:

```java
                            new Row(
                                rs.getString("id"),
                                rs.getLong("record_id"),
                                rs.getString("state"),
                                rs.getString("path"),
                                fromDb(rs.getString("variant")),
                                rs.getString("lang"),
                                rs.getString("error")),
```

In `claimNext` (lines 301-325), change `RETURNING` to:

```sql
            RETURNING id::text AS id, record_id, variant, lang,
                      array_to_string(page_ids, ',') AS page_ids, fingerprint, created_at
```

and the mapper to:

```java
                new Claimed(
                    rs.getString("id"),
                    rs.getLong("record_id"),
                    fromDb(rs.getString("variant")),
                    rs.getString("lang"),
                    ids(rs.getString("page_ids")),
                    rs.getString("fingerprint"),
                    rs.getTimestamp("created_at").toInstant()))
```

After `markFailed` (ends line 351), add:

```java
  /**
   * Records what the pages are after a build translated some of them, so an identical request
   * reuses this export rather than building it again.
   */
  public void refingerprint(String id, String fingerprint) {
    jdbc.update(
        "UPDATE pdf_export SET fingerprint = ? WHERE id = ?::uuid AND state = 'building'",
        fingerprint,
        id);
  }
```

- [ ] **Step 5: Keep the worker and controller compiling**

`PdfExportWorker.java` line 80:

```java
      String now =
          queue.fingerprint(
              export.recordId(), export.pageIds(), export.lang(), export.requestedAt());
```

`PdfExportController.java` line 102:

```java
            "record-"
                + row.recordId()
                + PdfExportQueue.fileSuffix(row.variant(), row.lang())
                + ".pdf";
```

`PdfExportWorkerFailureTest.java`: add `import java.time.Instant;` and replace lines 46-50 with:

```java
    when(queue.claimNext())
        .thenReturn(
            Optional.of(
                new PdfExportQueue.Claimed(
                    "id-1", 1L, Variant.ORIGINAL, "en", List.of(10L), "fp", Instant.EPOCH)));
    when(queue.fingerprint(1L, List.of(10L), "en", Instant.EPOCH)).thenReturn("fp");
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*PdfExportQueueTest' --tests '*PdfExportWorkerTest' --tests '*PdfExportWorkerFailureTest' --tests '*PdfExportApiTest' --tests '*PdfExportServiceTest'`
Expected: BUILD SUCCESSFUL. The existing fingerprint, reuse and naming tests pass unchanged, which shows the English path is identical.

- [ ] **Step 7: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "Exports carry a language: parsed, fingerprinted, reused and named by it"
```

---

## Task 5: Rendering in a language: PdfExportService and the cover sheet

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java:71-87,113-131,192-279,404-443` and add helpers after `newRenderer` (line 63)
- Modify: `backend/src/main/java/place/icomb/archiver/service/CoverSheetService.java:141-159,169,225-265,416-424`
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java:92-93`
- Modify: `backend/src/test/java/place/icomb/archiver/service/PdfExportWorkerFailureTest.java:59,73-77,93-97,110`
- Test: `backend/src/test/java/place/icomb/archiver/service/PdfExportServiceTest.java`

**Interfaces:**
- Consumes: `TranslationService.bestByRecord(Long, String)`, `modelBreakdown(Long, String)` (Task 3); `Claimed.lang()` (Task 4); `CoverSheetService.languageName(String)`; `MarkdownPdfRenderer.drawText(PDPageContentStream, String, float, float, float, boolean)`.
- Produces:
  - `public int PdfExportService.buildToFile(Long recordId, List<Integer> seqNumbers, Variant variant, String lang, Path target, Path scratchDir) throws IOException`
  - `static String PdfExportService.textLabel(String lang, boolean transcription)`, e.g. `"Czech translation"`, `"German transcription"`
  - `public CoverSheetService.Cover gather(Long recordId, List<Integer> selectedSeqs, String lang)`; `Cover` gains trailing components `String textLanguage, boolean transcriptionShown`

- [ ] **Step 1: Write the failing tests**

In `PdfExportServiceTest.java`, replace the `build` helper (lines 171-178) with:

```java
  private byte[] build(Long recordId, List<Integer> seqNumbers, PdfExportService.Variant variant)
      throws Exception {
    return build(recordId, seqNumbers, variant, "en");
  }

  private byte[] build(
      Long recordId, List<Integer> seqNumbers, PdfExportService.Variant variant, String lang)
      throws Exception {
    Path dir = Files.createTempDirectory("pdf-build");
    Path out = dir.resolve("out.pdf");
    pdfExportService.buildToFile(recordId, seqNumbers, variant, lang, out, dir);
    return Files.readAllBytes(out);
  }
```

At lines 459, 474, 484 and 498-499, add `"en"` after the variant argument of each `buildToFile(...)` call. For example, line 459 becomes `pdfExportService.buildToFile(recordId, List.of(1), variant, "en", out, dir);`.

Append these tests:

```java
  // --- other languages ---------------------------------------------------------------------

  private static final String DIACRITICS =
      "Žluťoučký kůň úpěl ďábelské ódy: ěščřžýáíéůúťďň ĚŠČŘŽÝÁÍÉŮÚŤĎŇ";

  private void czech(String text) {
    jdbc.update(
        """
        INSERT INTO page_translation (page_id, lang, model, text, created_at)
        VALUES ((SELECT id FROM page WHERE record_id = ? AND seq = 1),
                'cs', 'mistral-medium-latest', ?, now())
        ON CONFLICT (page_id, lang, model) DO UPDATE SET text = EXCLUDED.text
        """,
        recordId,
        text);
  }

  @Test
  void aCzechExportShowsTheStoredCzechAndSaysSo() throws Exception {
    czech("# Vyvlastnění české šlechty\n\nProti 10 nejvýznamnějším šlechticům.");

    for (PdfExportService.Variant v :
        List.of(PdfExportService.Variant.TRANSLATION, PdfExportService.Variant.SIDE_BY_SIDE)) {
      try (PDDocument doc = Loader.loadPDF(build(recordId, List.of(1), v, "cs"))) {
        String cover = pageText(doc, 1);
        String page = pageText(doc, 2);
        assertThat(page).as("%s text", v).contains("Vyvlastnění české šlechty");
        assertThat(page).as("%s label", v).contains("Czech translation");
        assertThat(page).as("%s no English", v).doesNotContain("Expropriation");
        assertThat(cover)
            .as("%s cover", v)
            .contains("Czech is machine translation (mistral-medium-latest)")
            .contains("German transcription is the authoritative text");
      }
    }
  }

  @Test
  void aGermanExportOfAGermanRecordShowsTheTranscription() throws Exception {
    for (PdfExportService.Variant v :
        List.of(PdfExportService.Variant.TRANSLATION, PdfExportService.Variant.SIDE_BY_SIDE)) {
      try (PDDocument doc = Loader.loadPDF(build(recordId, List.of(1), v, "de"))) {
        String page = pageText(doc, 2);
        assertThat(page).as("%s text", v).contains("Gegen 10 der wichtigsten Adligen");
        assertThat(page).as("%s label", v).contains("German transcription");
        assertThat(page).as("%s no English", v).doesNotContain("Expropriation");
        assertThat(pageText(doc, 1))
            .as("%s cover", v)
            .contains("German transcription itself, not a translation")
            .doesNotContain("machine translation");
      }
    }
  }

  @Test
  void aCzechExportOfAnUntranslatedPageShowsTheTranscriptionNotTheEnglish() throws Exception {
    try (PDDocument doc =
        Loader.loadPDF(build(recordId, List.of(1), PdfExportService.Variant.TRANSLATION, "cs"))) {
      String page = pageText(doc, 2);
      assertThat(page).contains("Gegen 10 der wichtigsten Adligen");
      assertThat(page).contains("[not translated - original text]");
      assertThat(page).doesNotContain("Expropriation");
    }
  }

  @Test
  void czechDiacriticsRenderInBodyAndHeadings() throws Exception {
    czech("# " + DIACRITICS + "\n\n" + DIACRITICS);
    try (PDDocument doc =
        Loader.loadPDF(build(recordId, List.of(1), PdfExportService.Variant.TRANSLATION, "cs"))) {
      String page = pageText(doc, 2).replaceAll("\\s+", " ");
      assertThat(page.split(java.util.regex.Pattern.quote(DIACRITICS), -1)).hasSize(3);
      assertThat(page).doesNotContain("?");
    }
  }

  @Test
  void theEnglishExportsAreUnchangedByCzechRows() throws Exception {
    for (PdfExportService.Variant v :
        List.of(PdfExportService.Variant.TRANSLATION, PdfExportService.Variant.SIDE_BY_SIDE)) {
      String before = textOf(build(recordId, List.of(1), v));
      czech("Český text lepšího modelu");
      String after = textOf(build(recordId, List.of(1), v));
      assertThat(after).as("%s", v).isEqualTo(before).doesNotContain("Český");
      jdbc.update("DELETE FROM page_translation WHERE lang = 'cs'");
    }
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportServiceTest'`
Expected: compilation FAILS with `method buildToFile ... cannot be applied to given types` (six arguments).

- [ ] **Step 3: Cover sheet**

`CoverSheetService.java`: add two trailing components to `Cover` (after `String selection` on line 158):

```java
      String selection,
      String textLanguage,
      boolean transcriptionShown) {}
```

Line 169 becomes `public Cover gather(Long recordId, List<Integer> selectedSeqs, String lang) {`.

Replace lines 225-245 (from the "models actually behind" comment through the `translated` assignment) with:

```java
    // The models actually behind this record's text in the export's language, named honestly: a
    // record can be part upgraded, and claiming the better model for pages that never got it would
    // misdescribe the evidence.
    boolean english = "en".equals(lang);
    boolean transcriptionShown = !english && lang.equals(str(r.get("lang")));
    String model =
        transcriptionShown ? "" : describeModels(translationService.modelBreakdown(recordId, lang));

    int transcribed =
        orZero(
            jdbc.queryForObject(
                "SELECT count(*) FROM page p JOIN page_text pt ON pt.page_id = p.id WHERE p.record_id = ?",
                Integer.class,
                recordId));
    int translated;
    if (transcriptionShown) {
      translated = 0;
    } else if (english) {
      translated =
          orZero(
              jdbc.queryForObject(
                  """
                  SELECT count(*) FROM page p JOIN page_text pt ON pt.page_id = p.id
                  WHERE p.record_id = ? AND pt.text_en IS NOT NULL AND pt.text_en <> ''
                  """,
                  Integer.class,
                  recordId));
    } else {
      translated =
          orZero(
              jdbc.queryForObject(
                  """
                  SELECT count(DISTINCT p.id) FROM page p
                  JOIN page_translation tr ON tr.page_id = p.id
                  WHERE p.record_id = ? AND tr.lang = ? AND tr.text <> ''
                  """,
                  Integer.class,
                  recordId,
                  lang));
    }
```

In the `new Cover(...)` call, change the last argument line from `describeSelection(selectedSeqs, pageCount));` to:

```java
        describeSelection(selectedSeqs, pageCount),
        languageName(lang),
        transcriptionShown);
```

Replace lines 416-424 (`if (c.translated() > 0) { ... }`) with:

```java
    if (c.transcriptionShown()) {
      out.add(
          "The text shown is the " + c.textLanguage() + " transcription itself, not a translation.");
    } else if (c.translated() > 0) {
      out.add(
          c.textLanguage()
              + " is machine translation"
              + (c.translationModel() == null || c.translationModel().isBlank()
                  ? ""
                  : " (" + c.translationModel() + ")")
              + ", not checked by a translator. The "
              + (c.language().isBlank() ? "original-language" : c.language())
              + " transcription is the authoritative text.");
    }
```

- [ ] **Step 4: PdfExportService**

After `newRenderer` (ends line 63), add:

```java
  static String textLabel(String lang, boolean transcription) {
    return CoverSheetService.languageName(lang) + (transcription ? " transcription" : " translation");
  }

  private boolean transcribedIn(Long recordId, String lang) {
    return !"en".equals(lang)
        && lang.equals(
            jdbcTemplate.queryForObject(
                "SELECT lang FROM record WHERE id = ?", String.class, recordId));
  }

  private java.util.Map<Integer, String> textsIn(Long recordId, String lang, boolean transcription) {
    if ("en".equals(lang)) {
      return translationService.bestEnglishByRecord(recordId);
    }
    if (!transcription) {
      return translationService.bestByRecord(recordId, lang);
    }
    java.util.Map<Integer, String> out = new java.util.HashMap<>();
    jdbcTemplate.query(
        "SELECT p.seq, pt.text_raw FROM page p JOIN page_text pt ON pt.page_id = p.id"
            + " WHERE p.record_id = ?",
        rs -> {
          if (rs.getString(2) != null) {
            out.put(rs.getInt(1), rs.getString(2));
          }
        },
        recordId);
    return out;
  }
```

`addCoverSheet` (lines 71-87): add a `String lang` parameter after `List<Integer> seqNumbers`, and change line 80 to `CoverSheetService.Cover cover = coverSheetService.gather(recordId, seqNumbers, lang);`.

`buildToFile` (lines 113-131): the signature becomes

```java
  public int buildToFile(
      Long recordId,
      List<Integer> seqNumbers,
      Variant variant,
      String lang,
      Path target,
      Path scratchDir)
      throws IOException {
```

and the switch becomes

```java
      switch (variant) {
        case ORIGINAL -> renderOriginal(doc, recordId, seqNumbers);
        case TRANSLATION -> renderTranslation(doc, recordId, seqNumbers, lang);
        case SIDE_BY_SIDE -> renderSideBySide(doc, recordId, seqNumbers, lang);
      }
```

Add `@param lang en, de or cs; the scans ignore it` to its Javadoc.

`renderSideBySide` (lines 192-279):
- The signature becomes `private void renderSideBySide(PDDocument doc, Long recordId, List<Integer> seqNumbers, String lang)`.
- Line 203 becomes `addCoverSheet(doc, renderer, landscape, recordId, seqNumbers, lang, margin);`.
- Replace line 207 with:

```java
      boolean english = "en".equals(lang);
      boolean transcription = transcribedIn(recordId, lang);
      String label = english ? "" : textLabel(lang, transcription);
      java.util.Map<Integer, String> best = textsIn(recordId, lang, transcription);
```

- Replace lines 225-228 with:

```java
        String shown = best.getOrDefault(seq, english ? (String) row.get("text_en") : null);
        String raw = (String) row.get("text_raw");
        boolean hasText = shown != null && !shown.isBlank();
        String rightText = hasText ? shown : (raw == null ? "" : raw);
        String heading = hasText ? label : "";
```

- After the right-half `drawElements` call (ends line 264), insert:

```java
            renderer.drawText(
                cs, heading, margin + halfWidth + gutter, contentTop + 8f, 7.5f, false);
```

(`drawText` returns at once for blank text, so an English page draws exactly what it drew before.)

Replace `renderEnglish` (lines 404-443) with:

```java
  private void renderTranslation(
      PDDocument doc, Long recordId, List<Integer> seqNumbers, String lang) throws IOException {
    {
      MarkdownPdfRenderer renderer = newRenderer(doc);
      addCoverSheet(doc, renderer, PDRectangle.A4, recordId, seqNumbers, lang, 56f);
      boolean english = "en".equals(lang);
      boolean transcription = transcribedIn(recordId, lang);
      String label = english ? "" : textLabel(lang, transcription);
      java.util.Map<Integer, String> best = textsIn(recordId, lang, transcription);
      for (int seq : seqNumbers) {
        List<java.util.Map<String, Object>> rows =
            jdbcTemplate.queryForList(
                """
                SELECT p.id AS page_id, pt.text_en, pt.text_raw
                FROM page p JOIN page_text pt ON pt.page_id = p.id
                WHERE p.record_id = ? AND p.seq = ?
                """,
                recordId,
                seq);

        String text = "";
        String note = "";
        Long pageId = null;
        if (!rows.isEmpty()) {
          pageId = ((Number) rows.get(0).get("page_id")).longValue();
          String shown =
              best.getOrDefault(seq, english ? (String) rows.get(0).get("text_en") : null);
          if (shown != null && !shown.isBlank()) {
            text = shown;
            note = label;
          } else {
            // Untranslated pages fall back to the original transcription rather than appearing
            // blank, so the export never silently omits a page's content.
            String raw = (String) rows.get(0).get("text_raw");
            text = raw == null ? "" : raw;
            note = raw == null || raw.isBlank() ? "[no text]" : "[not translated - original text]";
          }
        } else {
          note = "[no text]";
        }
        renderer.renderPage(
            doc, text, note, pageId, publicUrl + "/records/" + recordId + "/pages/" + seq, seq);
      }
    }
  }
```

- [ ] **Step 5: Worker and its failure test follow the new signature**

`PdfExportWorker.java` lines 92-93:

```java
      int pdfPages =
          pdfExportService.buildToFile(
              export.recordId(), seqs, export.variant(), export.lang(), partial, temp);
```

`PdfExportWorkerFailureTest.java`: in all four stubs (lines 59, 77, 97, 110), change `buildToFile(any(), any(), any(), any(), any())` to `buildToFile(any(), any(), any(), any(), any(), any())`. At lines 73 and 93, change `invocation.getArgument(3)` to `invocation.getArgument(4)`.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*PdfExportServiceTest' --tests '*PdfExportWorkerTest' --tests '*PdfExportWorkerFailureTest' --tests '*PdfExportApiTest'`
Expected: BUILD SUCCESSFUL. The existing English assertions (cover disclaimer, footer, best translation, continuation) pass unchanged.

- [ ] **Step 7: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "Render translation and side-by-side PDFs in English, German or Czech"
```

---

## Task 6: The worker translates missing pages while it builds

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java:15-25,36-53,85-89,111-118`
- Modify: `backend/src/test/java/place/icomb/archiver/service/PdfExportWorkerFailureTest.java:37-43`
- Create: `backend/src/test/java/place/icomb/archiver/service/PdfExportTranslationTest.java`

**Interfaces:**
- Consumes: `TranslationService.translateMissing(long, List<Long>, String)` (Task 3); `PdfExportQueue.fingerprint(long, List<Long>, String)`, `refingerprint(String, String)`, `Claimed.lang()`, `Claimed.requestedAt()` (Task 4); `buildToFile(..., lang, ...)` (Task 5); `TranslatorStub` (Task 3).
- Produces: `public PdfExportWorker(PdfExportQueue queue, PdfExportService pdfExportService, TranslationService translationService, StorageService storageService, long ttlHours, long minFreeBytes)`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/place/icomb/archiver/service/PdfExportTranslationTest.java`:

```java
package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.TranslatorStub;
import place.icomb.archiver.service.PdfExportService.Variant;

/** Exports in a language the archive does not hold yet, against a stubbed model. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportTranslationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  static WireMockServer model =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    model.start();
  }

  @AfterAll
  static void stop() {
    model.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(TranslatorStub.KEY_ENV, () -> "stub-key");
  }

  @Autowired private PdfExportWorker worker;
  @Autowired private PdfExportQueue queue;
  @Autowired private StorageService storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    ReflectionTestUtils.setField(worker, "minFreeBytes", 0L);
    model.resetAll();
    TranslatorStub.register(jdbc, model);
    TranslatorStub.reply(model, "Překlad:\n\nČeský text");
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    storage.clearExportTemp();
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private PdfExportQueue.View build(Variant variant, List<Long> selection, String lang) {
    String id = queue.request(record, variant, selection, lang).view().id();
    worker.runOnce();
    return queue.find(id).orElseThrow();
  }

  private String textOf(PdfExportQueue.View view) throws Exception {
    Path file = storage.exportFile(queue.row(view.id()).orElseThrow().path());
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private int czechRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM page_translation t JOIN page p ON p.id = t.page_id"
            + " WHERE p.record_id = ? AND t.lang = 'cs'",
        Integer.class,
        record);
  }

  @Test
  void aCzechExportAsksTheModelOncePerPageAndTheNextExportNotAtAll() throws Exception {
    PdfExportQueue.View first = build(Variant.TRANSLATION, pages, "cs");
    assertThat(first.state()).as("error: %s", first.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
    assertThat(czechRows()).isEqualTo(3);
    assertThat(textOf(first)).contains("Český text").doesNotContain("Překlad:");

    PdfExportQueue.View other = build(Variant.SIDE_BY_SIDE, pages.subList(0, 2), "cs");
    assertThat(other.state()).as("error: %s", other.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);

    PdfExportQueue.Requested again = queue.request(record, Variant.TRANSLATION, pages, "cs");
    assertThat(again.created()).isFalse();
    assertThat(again.view().id()).isEqualTo(first.id());
  }

  @Test
  void aGermanExportOfAGermanRecordAsksNothingAndShowsTheTranscription() throws Exception {
    PdfExportQueue.View view = build(Variant.TRANSLATION, pages, "de");
    assertThat(view.state()).as("error: %s", view.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isZero();
    assertThat(textOf(view))
        .contains("Text 1")
        .contains("German transcription")
        .doesNotContain("One");
  }

  @Test
  void aBlankPageIsNeverSentToTheModel() {
    jdbc.update("UPDATE page_text SET text_raw = '' WHERE page_id = ?", pages.get(1));
    PdfExportQueue.View view = build(Variant.TRANSLATION, pages, "cs");
    assertThat(view.state()).as("error: %s", view.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
  }

  @Test
  void aFailedCallFailsTheExportReadablyAndKeepsWhatWasAlreadyPaidFor() {
    model.resetAll();
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson(TranslatorStub.body("Český text")))
            .willSetStateTo("exhausted"));
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs("exhausted")
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"quota exceeded\"}")));

    PdfExportQueue.View failed = build(Variant.TRANSLATION, pages, "cs");
    assertThat(failed.state()).isEqualTo("failed");
    assertThat(failed.error())
        .contains("page 2")
        .contains("Czech")
        .contains("HTTP 400")
        .contains("quota exceeded");
    assertThat(czechRows()).isEqualTo(1);

    model.resetAll();
    TranslatorStub.reply(model, "Český text");
    PdfExportQueue.View retried = build(Variant.TRANSLATION, pages, "cs");
    assertThat(retried.state()).as("error: %s", retried.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
    assertThat(
            model.findAll(
                postRequestedFor(urlEqualTo(TranslatorStub.PATH))
                    .withRequestBody(containing("Text 1"))))
        .isEmpty();
  }

  @Test
  void withNoTranslatorConfiguredTheExportFailsSayingSo() {
    TranslatorStub.unregister(jdbc);
    PdfExportQueue.View view = build(Variant.SIDE_BY_SIDE, pages, "cs");
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).contains("No translation model is configured");
    assertThat(TranslatorStub.calls(model)).isZero();
  }

  @Test
  void anExportQueuedBehindOneThatTranslatesStillBuilds() {
    String a = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    String b = queue.request(record, Variant.SIDE_BY_SIDE, pages, "cs").view().id();

    worker.runOnce();
    worker.runOnce();

    PdfExportQueue.View second = queue.find(b).orElseThrow();
    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(second.state()).as("error: %s", second.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }

  @Test
  void twoExportsBuildingAtOnceTranslateEachPageOnce() throws Exception {
    model.resetAll();
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .willReturn(okJson(TranslatorStub.body("Český text")).withFixedDelay(300)));
    String a = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    String b = queue.request(record, Variant.SIDE_BY_SIDE, pages, "cs").view().id();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> runs = List.of(worker::runOnce, worker::runOnce);
      for (Future<Boolean> run : pool.invokeAll(runs)) {
        run.get();
      }
    } finally {
      pool.shutdown();
    }

    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(b).orElseThrow().state()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportTranslationTest'`
Expected: every test except `aGermanExportOfAGermanRecordAsksNothingAndShowsTheTranscription` FAILS, because no model call is made yet (the call count is 0, or the export is `ready` with the transcription fallback). The German test already passes, which is correct: it asks for nothing.

- [ ] **Step 3: Implement**

`PdfExportWorker.java`: add `"Pages a translation in another language still needs are translated first, once, and kept."` to the class Javadoc (lines 15-25). Add a `TranslationService` field and constructor parameter:

```java
  private final PdfExportQueue queue;
  private final PdfExportService pdfExportService;
  private final TranslationService translationService;
  private final StorageService storageService;
  private final Duration ttl;
  private long minFreeBytes;

  public PdfExportWorker(
      PdfExportQueue queue,
      PdfExportService pdfExportService,
      TranslationService translationService,
      StorageService storageService,
      @Value("${archiver.pdf-export.ttl-hours:24}") long ttlHours,
      @Value("${archiver.pdf-export.min-free-bytes:2147483648}") long minFreeBytes) {
    this.queue = queue;
    this.pdfExportService = pdfExportService;
    this.translationService = translationService;
    this.storageService = storageService;
    this.ttl = Duration.ofHours(ttlHours);
    this.minFreeBytes = minFreeBytes;
  }
```

After the `seqs` size check (ends line 88), insert:

```java
      if (translationService.translateMissing(export.recordId(), export.pageIds(), export.lang())
          > 0) {
        queue.refingerprint(
            export.id(), queue.fingerprint(export.recordId(), export.pageIds(), export.lang()));
      }
```

In the success log (lines 111-118), change the format to `"PDF export {} ready: record={} variant={} lang={} pages={} bytes={} ({}ms)"` and add `export.lang()` after `export.variant()`.

`PdfExportWorkerFailureTest.java`: add a field `private TranslationService translationService;`. In `setup()` add `translationService = mock(TranslationService.class);` and change the construction (line 43) to:

```java
    worker =
        new PdfExportWorker(queue, pdfExportService, translationService, storageService, 24, 0);
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*PdfExportTranslationTest' --tests '*PdfExportWorkerTest' --tests '*PdfExportWorkerFailureTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "Translate missing pages while building a Czech or German export"
```

---

## Task 7: The REST endpoint and the MCP tools take and report lang

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java:55-77,103-107,118-128`
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java:261-306,330,382-397`
- Modify: `backend/src/test/java/place/icomb/archiver/mcp/McpContentToolsTest.java:125,140,142,150,170`, `backend/src/test/java/place/icomb/archiver/mcp/McpDownloadPartsTest.java:76`
- Test: `backend/src/test/java/place/icomb/archiver/controller/PdfExportApiTest.java`, `backend/src/test/java/place/icomb/archiver/mcp/McpContentToolsTest.java`

**Interfaces:**
- Consumes: `PdfExportQueue.parseVariant`, `resolveLang`, `request(long, Variant, List<Long>, String)`, `View.lang()`, `Row.lang()`, `fileSuffix(Variant, String)` (Task 4).
- Produces:
  - `POST /api/records/{recordId}/pdf-exports` with body `{"variant": "original" | "translation" | "english" | "side-by-side", "lang": "en" | "de" | "cs", "pages": "1,3,5-10"}`. Every field is optional. The answer is `202`/`200` `{id, state, pageCount, variant, lang, bytes, expiresAt, error}`, or `400 {error}` for an unknown lang, `english` with another lang, an unknown variant or a malformed range. `variant` reports `english` for an English translation and `translation` for de/cs.
  - `GET /api/pdf-exports/{id}` → the same view, with `lang`.
  - `GET /api/pdf-exports/{id}/file` → the PDF with `Content-Disposition: attachment; filename="record-<id><suffix>.pdf"` and `Content-Language: <lang>`.
  - `Map<String, Object> ArchiverMcpTools.exportPdf(Long recordId, String variant, String pages, String lang)`. `get_pdf_export` carries `lang`; the `download_pdf` text carries `; lang <xx>`.

- [ ] **Step 1: Write the failing tests**

Add to `PdfExportApiTest.java`:

```java
  // --- languages ---------------------------------------------------------------------------

  @Test
  void aGermanTranslationOfAGermanRecordIsNamedForItsLanguage() throws Exception {
    JsonNode made = created("{\"variant\":\"translation\",\"lang\":\"de\"}", 202);
    assertThat(made.get("lang").asText()).isEqualTo("de");
    assertThat(made.get("variant").asText()).isEqualTo("translation");
    String both =
        created("{\"variant\":\"side-by-side\",\"lang\":\"de\"}", 202).get("id").asText();
    worker.drain();

    HttpResponse<byte[]> file = getBytes("/pdf-exports/" + made.get("id").asText() + "/file");
    assertThat(file.headers().firstValue("Content-Disposition").orElse(""))
        .contains("record-" + record + "-german.pdf");
    assertThat(file.headers().firstValue("Content-Language").orElse("")).isEqualTo("de");
    assertThat(
            getBytes("/pdf-exports/" + both + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-original-and-german.pdf");
  }

  @Test
  void aCzechTranslationAlreadyHeldIsNamedCzech() throws Exception {
    jdbc.update(
        "INSERT INTO page_translation (page_id, lang, model, text)"
            + " SELECT id, 'cs', 'stored-model', 'Český text' FROM page WHERE record_id = ?",
        record);
    String one = created("{\"variant\":\"translation\",\"lang\":\"cs\"}", 202).get("id").asText();
    String both =
        created("{\"variant\":\"side-by-side\",\"lang\":\"cs\"}", 202).get("id").asText();
    worker.drain();

    assertThat(
            getBytes("/pdf-exports/" + one + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-czech.pdf");
    assertThat(
            getBytes("/pdf-exports/" + both + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-original-and-czech.pdf");
  }

  @Test
  void anUnknownLanguageIsRefusedAndNothingIsQueued() throws Exception {
    HttpResponse<String> resp =
        post(
            "/records/" + record + "/pdf-exports",
            "{\"variant\":\"translation\",\"lang\":\"fr\"}",
            true);
    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(json.readTree(resp.body()).get("error").asText()).contains("en, de, cs");
    assertThat(exports()).isZero();
  }

  @Test
  void englishPairedWithAnotherLanguageIsRefused() throws Exception {
    HttpResponse<String> resp =
        post(
            "/records/" + record + "/pdf-exports",
            "{\"variant\":\"english\",\"lang\":\"cs\"}",
            true);
    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(json.readTree(resp.body()).get("error").asText()).contains("translation");
    assertThat(exports()).isZero();
  }

  @Test
  void anEnglishExportAnswersAsItAlwaysDid() throws Exception {
    JsonNode english = created("{\"variant\":\"english\"}", 202);
    assertThat(english.get("variant").asText()).isEqualTo("english");
    assertThat(english.get("lang").asText()).isEqualTo("en");
    assertThat(created("{\"variant\":\"translation\"}", 202).get("id").asText())
        .isEqualTo(english.get("id").asText());
    assertThat(created("{\"variant\":\"original\",\"lang\":\"cs\"}", 202).get("lang").asText())
        .isEqualTo("en");
  }
```

In `McpContentToolsTest.java`, add `, null` as a fourth argument to every `tools.exportPdf(...)` call (lines 125, 140, 142, 150, 170). Do the same in `McpDownloadPartsTest.java` line 76. Then add:

```java
  @Test
  void aGermanPdfOfAGermanRecordReportsItsLanguage() {
    Map<String, Object> asked = tools.exportPdf(record, "translation", "2", "de");
    assertThat(asked.get("lang")).isEqualTo("de");
    assertThat(asked.get("variant")).isEqualTo("translation");
    String id = String.valueOf(asked.get("id"));

    worker.runOnce();

    assertThat(tools.getPdfExport(id).get("lang")).isEqualTo("de");
    String text =
        tools.downloadPdf(id, null).content().stream()
            .filter(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent)
            .map(c -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) c).text())
            .findFirst()
            .orElseThrow();
    assertThat(text).contains("lang de");
  }

  @Test
  void anUnknownLanguageIsRefusedNamingTheAcceptedOnes() {
    assertThatThrownBy(() -> tools.exportPdf(record, "translation", "2", "fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("en, de, cs");
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportApiTest' --tests '*McpContentToolsTest' --tests '*McpDownloadPartsTest'`
Expected: compilation FAILS on `exportPdf(Long,String,String,String)`. When that is fixed and before the controller changes, the API tests fail because `made.get("lang")` is null.

- [ ] **Step 3: Controller**

Replace `PdfExportController.java` lines 55-62 with:

```java
    Variant variant;
    String lang;
    String pages;
    try {
      String asked = text(in, "variant");
      variant = PdfExportQueue.parseVariant(asked);
      lang = PdfExportQueue.resolveLang(asked, text(in, "lang"));
      pages = text(in, "pages");
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }
```

Line 74 becomes `PdfExportQueue.Requested requested = queue.request(recordId, variant, pageIds, lang);`.

In the `"ready"` branch (lines 103-107), add the header after `.contentType(...)`:

```java
            .header(HttpHeaders.CONTENT_LANGUAGE, row.lang())
```

In `describe` (lines 118-128), after `out.put("variant", v.variant());` add:

```java
    out.put("lang", v.lang());
```

- [ ] **Step 4: MCP tools**

Replace `export_pdf` (lines 261-291) with:

```java
  @McpTool(
      name = "export_pdf",
      title = "Export PDF",
      description =
          "Ask for a PDF of a record, or of chosen pages. variant is 'original' (the scans with a"
              + " searchable text layer, default), 'translation' or 'side-by-side' (scan facing"
              + " text); 'english' still means the English translation. lang is the language of"
              + " the text in a translation or side-by-side PDF: 'en' (default), 'de' or 'cs'. In"
              + " the record's own language the text is its transcription; otherwise a"
              + " translation the archive does not hold yet is made while the PDF is built, once"
              + " per page, and kept. pages takes ranges and comma lists such as '14' or"
              + " '1,3,5-10'; omit it for the whole record. The PDF is built in the background:"
              + " poll get_pdf_export with the id until state is 'ready', then fetch downloadUrl"
              + " with the same MCP token. An identical request is reused. A finished PDF is kept"
              + " 24 hours.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Export PDF",
              readOnlyHint = false,
              destructiveHint = false,
              idempotentHint = true))
  public Map<String, Object> exportPdf(
      @McpToolParam(description = "Record id") Long recordId,
      @McpToolParam(description = "original, translation or side-by-side", required = false)
          String variant,
      @McpToolParam(description = "Page selection, e.g. '14' or '1,3,5-10'", required = false)
          String pages,
      @McpToolParam(description = "Language of the text: en, de or cs", required = false)
          String lang) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (variant != null) {
      body.put("variant", variant);
    }
    if (pages != null) {
      body.put("pages", pages);
    }
    if (lang != null) {
      body.put("lang", lang);
    }
    return pdfView(pdfExportController.create(recordId, body));
  }
```

In the `get_pdf_export` description (lines 297-298), change the sentence to `"State of a PDF requested with export_pdf: queued, building, ready, failed or expired, and its lang."`.

In `downloadPdf`, after line 330 (`ResponseEntity<?> response = pdfExportController.file(id);`), add:

```java
    String lang = response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_LANGUAGE);
```

In the text content (lines 384-394), change `+ sha256` to `+ sha256 + (lang == null ? "" : "; lang " + lang)`. The line then reads `... sha256 <hex>; lang en)`, and the existing `McpDownloadPartsTest` pattern `sha256 ([0-9a-f]{64})` still matches.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `cd backend && ./gradlew test --tests '*PdfExportApiTest' --tests '*McpContentToolsTest' --tests '*McpDownloadPartsTest' --tests '*McpBearerOnApiTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "Accept and report lang on the PDF export endpoint and MCP tools"
```

---

## Task 8: The frontend language choice

**Files:**
- Modify: `frontend/src/lib/pdf-export.ts:42-46`
- Modify: `frontend/src/lib/components/PdfExportButton.svelte:17-29,54`
- Modify: `frontend/src/routes/records/[id]/+page.svelte:31-41,186-194,202-215`
- Modify: `frontend/src/lib/messages/en.ts:59`, `frontend/src/lib/messages/de.ts:61`, `frontend/src/lib/messages/cs.ts:61`
- Test: `frontend/tests/pdf-export.test.ts`, `frontend/tests/PdfExportButton.test.ts`

**Interfaces:**
- Consumes: the REST body field `lang` (Task 7).
- Produces: `exportRequest(variant: string, pages: string, lang: string = 'en'): { variant: string; pages?: string; lang?: string }`. It omits `lang` when it is `en` or when the variant is `original`. `PdfExportButton` gains the prop `lang?: string` (default `'en'`). Message keys: `record.exportLangTitle`, `record.exportLangEn`, `record.exportLangDe`, `record.exportLangCs`.

- [ ] **Step 1: Write the failing tests**

In `frontend/tests/pdf-export.test.ts`, inside `describe('exportRequest', ...)`, add:

```ts
	it('names a language only when it is not English and the export has text', () => {
		expect(exportRequest('translation', '', 'en')).toEqual({ variant: 'translation' });
		expect(exportRequest('translation', '2', 'cs')).toEqual({
			variant: 'translation',
			pages: '2',
			lang: 'cs'
		});
		expect(exportRequest('side-by-side', '', 'de')).toEqual({ variant: 'side-by-side', lang: 'de' });
		expect(exportRequest('original', '', 'cs')).toEqual({ variant: 'original' });
	});
```

In `frontend/tests/PdfExportButton.test.ts`, inside `describe('PdfExportButton', ...)`, add:

```ts
	it('asks for the chosen language', async () => {
		fetchMock.mockResolvedValueOnce(json(202, { id: 'abc', state: 'ready', error: null }));

		render(PdfExportButton, {
			recordId: 7,
			variant: 'translation',
			pages: '',
			lang: 'cs',
			label: 'Get PDF'
		});
		await fireEvent.click(screen.getByRole('button'));
		await vi.runAllTimersAsync();

		expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
			variant: 'translation',
			lang: 'cs'
		});
	});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && bun run test`
Expected: the two new tests FAIL, because the body has no `lang`. `exportRequest` ignores its third argument.

- [ ] **Step 3: Implement**

`frontend/src/lib/pdf-export.ts` lines 42-46 become:

```ts
/** The request body: the whole record unless pages are given, English unless told otherwise. */
export function exportRequest(
	variant: string,
	pages: string,
	lang: string = 'en'
): { variant: string; pages?: string; lang?: string } {
	const body: { variant: string; pages?: string; lang?: string } = { variant };
	const trimmed = pages.trim();
	if (trimmed) body.pages = trimmed;
	if (lang !== 'en' && variant !== 'original') body.lang = lang;
	return body;
}
```

`frontend/src/lib/components/PdfExportButton.svelte` lines 17-29 become:

```svelte
	let {
		recordId,
		variant = 'original',
		pages = '',
		lang = 'en',
		label,
		class: klass = 'vui-btn vui-btn-primary vui-btn-sm'
	}: {
		recordId: number;
		variant?: string;
		pages?: string;
		lang?: string;
		label: string;
		class?: string;
	} = $props();
```

and line 54 becomes `body: JSON.stringify(exportRequest(variant, pages, lang))`.

Messages. In `en.ts`, after line 59 (`'record.pages': 'Pages',`) add:

```ts
	'record.exportLangTitle':
		'Language of the text. A translation the archive does not hold yet is made while the PDF is built.',
	'record.exportLangEn': 'English',
	'record.exportLangDe': 'German',
	'record.exportLangCs': 'Czech',
```

In `de.ts`, after line 61 (`'record.pages': 'Seiten',`) add:

```ts
	'record.exportLangTitle':
		'Sprache des Textes. Eine noch nicht vorhandene Übersetzung wird beim Erstellen des PDF angefertigt.',
	'record.exportLangEn': 'Englisch',
	'record.exportLangDe': 'Deutsch',
	'record.exportLangCs': 'Tschechisch',
```

In `cs.ts`, after line 61 (`'record.pages': 'Strany',`) add:

```ts
	'record.exportLangTitle': 'Jazyk textu. Překlad, který archiv ještě nemá, se pořídí při tvorbě PDF.',
	'record.exportLangEn': 'Angličtina',
	'record.exportLangDe': 'Němčina',
	'record.exportLangCs': 'Čeština',
```

`frontend/src/routes/records/[id]/+page.svelte` lines 31-41 become:

```svelte
	let exportPages = $state('');
	let exportVariant = $state<'original' | 'translation' | 'side-by-side'>('original');
	let exportLang = $state<'en' | 'de' | 'cs'>('en');
	let exportLangName = $derived(
		exportLang === 'de'
			? $t('record.exportLangDe')
			: exportLang === 'cs'
				? $t('record.exportLangCs')
				: $t('record.exportLangEn')
	);

	let downloadLabel = $derived(
		exportVariant === 'translation'
			? `Download ${exportLangName}`
			: exportVariant === 'side-by-side'
				? 'Download side by side'
				: 'Download PDF'
	);
```

Lines 186-194 (the variant `<select>`) become:

```svelte
			<select
				bind:value={exportVariant}
				class="px-2 py-1.5 rounded-md border border-border bg-bg-deep text-text text-[length:var(--vui-text-sm)] focus:outline-none focus:ring-1 focus:ring-accent"
				title="Original scans with a searchable text layer, the text as a translation or transcription, or both facing each other"
			>
				<option value="original">Original scans</option>
				<option value="translation">Translation</option>
				<option value="side-by-side">Side by side</option>
			</select>
			{#if exportVariant !== 'original'}
				<select
					bind:value={exportLang}
					class="px-2 py-1.5 rounded-md border border-border bg-bg-deep text-text text-[length:var(--vui-text-sm)] focus:outline-none focus:ring-1 focus:ring-accent"
					title={$t('record.exportLangTitle')}
				>
					<option value="en">{$t('record.exportLangEn')}</option>
					<option value="de">{$t('record.exportLangDe')}</option>
					<option value="cs">{$t('record.exportLangCs')}</option>
				</select>
			{/if}
```

(These are the same classes the variant select already uses, verbatim. There are no new classes and no `style=`.)

In both `<PdfExportButton ...>` usages (lines 202-207 and 211-217), add `lang={exportLang}` after `variant={exportVariant}`.

**Layout (added 5 Nov 2026 at the user's request; supersedes the placement implied above).** The
record page currently stacks two bars: the action bar (page picker, variant select, download
buttons, kept-pages button, then Source link and admin reset menu on the right) and, below it, the
"Translation quality" panel. Rearrange them into one row of two panels:

- A wrapper `grid grid-cols-1 md:grid-cols-2 gap-4 mb-6` holding two cards, each with the existing
  card classes (`py-3 px-4 rounded-lg bg-surface border border-border vui-animate-fade-in`).
- **Left card: Translation quality**, moved unchanged in content (the same markup, `tq-*` classes,
  form and hint).
- **Right card: Export.** A heading in the same style as the Translation quality heading (icon
  `Download`, uppercase xs label, text "Export PDF" via a new i18n key `record.exportTitle` in
  en/de/cs). Below it, labelled fields so the controls explain themselves: **Pages** (the existing
  input; empty means the whole record), **Format** (the variant select: Original scans / Translation /
  Side by side), and **Language** (the lang select, shown only when the format is not Original).
  Labels use new i18n keys `record.exportPagesLabel`, `record.exportFormatLabel`,
  `record.exportLangLabel`. Then the download button and the kept-pages button with its clear button.
- When there is no `translationStatus` (no left card), the Export card takes the full width
  (`md:col-span-2`).
- The **Source** link and the **admin reset menu** stay in a slim bar above the grid, right-aligned,
  shown when `record.sourceUrl || isAdmin`. The admin dropdown's behaviour is unchanged.
- Hide the Export card entirely when `pages.length === 0`, as now.
- Only existing utility classes and `vui-*` classes; no new CSS unless a scoped `<style>` is needed
  for the field labels, and no `style=` attribute.
- After the change, run the frontend locally (`bun run dev` against the test backend or a mocked
  load) or at least `bun run check`, and describe the resulting layout in the report.

- [ ] **Step 4: Run the checks and watch them pass**

Run: `cd frontend && bun run test && bun test tests/i18n-completeness.test.ts && bun run check`
Expected: vitest all green; `PASSED: All i18n keys are complete in both languages`; svelte-check `0 errors`.

Run: `cd frontend && bunx prettier --write src/lib/pdf-export.ts src/lib/components/PdfExportButton.svelte 'src/routes/records/[id]/+page.svelte' src/lib/messages tests/pdf-export.test.ts tests/PdfExportButton.test.ts && bunx eslint src tests`
Expected: no eslint errors.

Check: `grep -n 'style="' 'frontend/src/routes/records/[id]/+page.svelte' frontend/src/lib/components/PdfExportButton.svelte` prints nothing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src frontend/tests
git commit -m "Choose English, German or Czech for translation and side-by-side PDFs"
```

---

## Task 9: Documentation

**Files:**
- Modify: `CLAUDE.md:115-121,194`
- Modify: `.claude/skills/archiver-api/SKILL.md:24,275-276`

**Interfaces:**
- Consumes: the behaviour shipped in Tasks 1-8.
- Produces: documentation only.

- [ ] **Step 1: CLAUDE.md**

After line 121 (`- Translation skipped if lang = "en"`), add:

```markdown
- `page_translation` holds every model's translation per page **and language** (`lang` in
  `en`/`de`/`cs`, text in `text`). Every pipeline, viewer, search and correction query reads
  `lang = 'en'`; `page_text.text_en` caches the best English only. German and Czech rows are made
  only by a PDF export that needs them (`TranslationService.translateMissing`), once per page,
  with the best configured TRANSLATION model, and never in the record's own language, where the
  export shows the transcription.
```

Line 194 becomes:

```markdown
- `service/PdfExportService.java` — all three PDF exports (original scans, translation, side-by-side; the last two in `en`, `de` or `cs`); every PDF is built on demand (`PdfExportQueue`/`PdfExportWorker`), nothing is stored per record
```

- [ ] **Step 2: archiver-api skill**

In line 24 of `.claude/skills/archiver-api/SKILL.md`, after `(pages: "14")` insert `, with \`variant: "translation"\` or \`"side-by-side"\` and \`lang: "en" | "de" | "cs"\``.

Replace lines 275-276 with:

```markdown
`variant` is `original` (the scans with a searchable text layer), `translation` (`english` is the
English alias) or `side-by-side`; `lang` is `en` (default), `de` or `cs` and applies to the last two.
In the record's own language the text is the transcription; a translation the archive does not
hold yet is made while the PDF is built, once per page, and kept for every later export. Every
field is optional (`original`, English, the whole record). `pages` takes ranges and comma lists
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md .claude/skills/archiver-api/SKILL.md
git commit -m "Document PDF exports in German and Czech"
```

---

## Task 10: Full verification

**Files:** none.

**Interfaces:** consumes everything above.

- [ ] **Step 1: Backend suite**

Run: `make test-backend`
Expected: BUILD SUCCESSFUL, with no test skipped that was running before.

- [ ] **Step 2: Linters**

Run: `make lint`
Expected: spotlessCheck, ruff, eslint and prettier all clean. If prettier flags a file changed here, run `cd frontend && bunx prettier --write <file>`, commit, and rerun.

- [ ] **Step 3: Frontend type check**

Run: `cd frontend && bun run check`
Expected: `svelte-check found 0 errors`.

- [ ] **Step 4: Frontend tests and i18n**

Run: `make test-frontend`
Expected: vitest green, and `PASSED: All i18n keys are complete in both languages`.

- [ ] **Step 5: Self-check against the spec**

Run each of these and confirm the expected result:
- `grep -rn "text_en" backend/src/main/java | grep page_translation`: nothing.
- `grep -rn -A4 "page_translation" backend/src/main/java`: read every SQL statement it prints. Each one either has a `lang` condition (`= 'en'`, `= ?`, or the insert's `lang` column) or is one of the two deliberate language-wide statements, `PipelineStateMachine.java:499` (the re-OCR delete) and `AiAdminController.java:325` (the usage guard).
- `git log --format=%B -n 9 | grep -i "co-authored\|claude"`: nothing.

- [ ] **Step 6: Commit any lint fixes**

```bash
git status --short
git add -A backend frontend
git commit -m "Lint fixes for multi-language PDF export"
```

(Only if Steps 1-4 changed files; skip otherwise.)

Deploying and checking a real `cs` export against production (one small record first, per the "prove one record before bulk" rule) is the release step after this plan. Do not run a bulk export.
