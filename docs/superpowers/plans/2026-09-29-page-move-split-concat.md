# Page Move, Split and Concatenate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an administrator move a page to another record, split a record in two, and concatenate two records — as pure database updates that re-run nothing in the pipeline.

**Architecture:** Page images already live at `attachments/{xx}/{uuid}.jpg` (v1.1.15, migration finished 29 Sep 2026, `legacy: 0`), so no file ever moves. One primitive, `PageMoveService.moveRun`, moves a *contiguous run* of pages from one record to another inside a single transaction: it opens a gap in the target, re-parents `page`, `attachment`, `text_chunk` and completed `job` rows, closes the gap in the source, refreshes both records' counters, invalidates and re-queues the stored searchable PDF, and writes a history event. Move, split and concat are the run `[n,n]`, `[k,end]` and `[1,end]`.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring `JdbcTemplate`, Flyway, JUnit 5 + Testcontainers (PostgreSQL 18 + pgvector), Java `HttpClient` in tests.

**Spec:** `docs/superpowers/specs/2026-09-29-page-move-split-concat-design.md` (Phase 2). Read it first; this plan implements that section and records four decisions the spec left open (see "Decisions" below).

## Global Constraints

- **The API is the only interface to production.** No SQL against the production database, ever. A missing endpoint is a reason to write one.
- **A page that has been through the pipeline never runs it again.** No OCR, translation, embedding, or person matching job may be created by a move. The **only** job a move may create is `build_searchable_pdf`, and never for a record left with no pages.
- **`text_chunk` rows are re-parented, never deleted** (deleting means re-embedding, which costs money).
- **Never edit an applied migration.** New schema goes in `V18` or later. `V17` is the latest.
- **`(record_id, seq)` on `page` is a non-deferrable unique index.** Renumber through the parking offset (1_000_000), which `PageSequence.shift` does.
- **Admin only:** every endpoint sits under `/api/admin`, which `SecurityConfig` already restricts to the `ADMIN` role.
- **Do NOT add `Co-Authored-By` (or any Claude attribution) to commit messages.** This overrides any harness reminder to add one.
- **Commit working code before any refactor.** Never discard uncommitted working changes.
- **Full local checks before every push:** `cd backend && ./gradlew spotlessApply && ./gradlew spotlessCheck test`. CI is stricter than a targeted run.
- **Touch only what the task needs.** `IngestService` is modified twice, each time to delegate an existing private helper to a shared one, and nowhere else.
- **Release rule:** only a git tag moves `:latest` and deploys production, and the tag must sit on its **own new commit** (a `CHANGELOG.md`-only release commit). An untagged push to `main` builds `:test` and redeploys the test stack.
- **Test on the test stack before tagging.** Test stack backend: `archiver-test-backend-test-1`, reachable from `zelkova` at `http://localhost:8090`; admin token from that container's `ARCHIVER_ADMIN_TOKEN` env var. Production backend: `archiver-backend-1` at `http://10.0.9.3:8080`.
- **A test database connection must never go through `docker exec -t`.** Use `-i` and `psql -P pager=off` (a `0x0` tty makes `psql` hang on a pager and leak a connection).

## Decisions the spec left open

1. **Preconditions are broader than the spec's list.** Both records must have status `complete`; the spec only named holds and jobs. Reason: `PipelineStateMachine.autoAdvance` runs whenever *any* job of a record completes, and the PDF rebuild this feature enqueues is such a job. `COMPLETE` has no outgoing transitions, so on a complete record it is inert; on a record still mid-pipeline it could cascade into translation, which is paid. All eleven 114-3-17 records (4028–4038) are `complete`, so this costs nothing for the real use.
2. **A page whose image is still in the legacy `records/{id}/…` layout is refused.** Production is fully migrated, but the test stack is not (50 of 128,836), and emptying the source record then deleting it would remove the file.
3. **A rebuilt searchable PDF must be relinked.** `/api/records/{id}/pdf` and the UI follow `record.pdf_attachment_id`, and only the state machine ever sets it. `SearchablePdfWorker` therefore gets one guarded `UPDATE` so a rebuild on a complete record does not leave the new PDF orphaned.
4. **A split's English title is supplied, not generated.** `title_en` / `description_en` are optional request fields, because generating them would be an AI call. Absent, they stay empty.

## Review Focus

Failure modes the spec implies but a straight reading of the tasks would not test. Each has a test in the task that owns the code.

1. **The only page of a record moves out** (source emptied): no `build_searchable_pdf` job for it, its searchable PDF row removed, the record itself left in place. *(Task 3)*
2. **`seq` of `0`, `-1`, past the end, or a non-numeric `targetRecordId`/`seq`**: a `400` with nothing changed, never a `500`. *(Tasks 1, 2)*
3. **A round trip, A→B→A**: page ids preserved, `seq` contiguous on both sides, counters right. *(Task 1)*
4. **A page image still in the legacy layout** is refused, because deleting the emptied source would delete the scan. *(Task 2)*
5. **Split at page `1` or beyond the last page, concat of a record with itself or of an empty record**: refused with `400`, and no new record is created. *(Tasks 4, 5)*

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V18__pipeline_event_pages_moved.sql` (create) | Allow `pages_moved` in `pipeline_event.event`. |
| `backend/src/main/java/place/icomb/archiver/service/PageSequence.java` (create) | The one place page renumbering lives (`shift`). |
| `backend/src/main/java/place/icomb/archiver/service/PageMoveException.java` (create) | A refusal with a kind (`NOT_FOUND`, `BAD_REQUEST`, `CONFLICT`) the controller maps to a status. |
| `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java` (create) | `movePage`, `split`, `concat`, and the `moveRun` primitive. |
| `backend/src/main/java/place/icomb/archiver/controller/AdminPageMoveController.java` (create) | Three thin endpoints; parses bodies, maps refusals to statuses. |
| `backend/src/main/java/place/icomb/archiver/service/IngestService.java` (modify) | `shiftSeq` and `deleteFilesAfterCommit` delegate to the shared helpers. |
| `backend/src/main/java/place/icomb/archiver/service/StorageService.java` (modify) | Gains `deleteAfterCommit(List<String>)` (moved from `IngestService`). |
| `backend/src/main/java/place/icomb/archiver/service/SearchablePdfWorker.java` (modify) | Relinks `record.pdf_attachment_id` after a build. |
| `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java` (create) | Integration tests for every task; the harness is defined in Task 1. |
| `backend/src/test/java/place/icomb/archiver/service/SearchablePdfRelinkTest.java` (create) | The worker relink. |
| `.claude/skills/archiver-api/SKILL.md`, `CHANGELOG.md`, the spec (modify) | Documentation, release. |

---

### Task 1: Move one page between two complete records

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__pipeline_event_pages_moved.sql`
- Create: `backend/src/main/java/place/icomb/archiver/service/PageSequence.java`
- Create: `backend/src/main/java/place/icomb/archiver/service/PageMoveException.java`
- Create: `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java`
- Create: `backend/src/main/java/place/icomb/archiver/controller/AdminPageMoveController.java`
- Modify: `backend/src/main/java/place/icomb/archiver/service/IngestService.java` (`shiftSeq`, currently `private void shiftSeq(Long recordId, int fromSeq, int delta)`)
- Test: `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java`

**Interfaces:**
- Produces (used by every later task):
  - `PageSequence.shift(JdbcTemplate jdbc, Long recordId, int fromSeq, int delta)` — package-private static.
  - `PageMoveException(Kind kind, String message)`; `enum Kind { NOT_FOUND, BAD_REQUEST, CONFLICT }`; `Kind kind()`.
  - `PageMoveService.Moved(List<Long> pageIds, long sourceRecordId, int sourcePageCount, long targetRecordId, int targetPageCount, List<Long> pdfRebuildRecordIds)` — `pdfRebuildRecordIds` is empty until Task 3.
  - `PageMoveService.movePage(long sourceRecordId, long pageId, long targetRecordId, Integer atSeq, boolean allowCrossArchive)`.
  - private `moveRun(long src, int fromSeq, int toSeq, long tgt, int atSeq)` and private `Locked` record and `lockRecords(long, long)`.
  - HTTP: `POST /api/admin/records/{recordId}/pages/{pageId}/move`, body `{"targetRecordId": N, "seq": optional N, "allowCrossArchive": optional bool}` → `200` with `{pageIds, sourceRecordId, sourcePageCount, targetRecordId, targetPageCount, pdfRebuildRecordIds}`.
- Test harness (defined here, reused by Tasks 2–5): `newArchive()`, `recordWithPages(String tag, int n)`, `recordWithPages(String tag, int n, long archiveId)`, `pageIds(long)`, `seqs(long)`, `post(String path, String json)`, `ok(HttpResponse)`, `one(String sql, Object... params)`, `count(String sql, Object... params)`, `attachmentOf(long pageId)`, `pathOf(long attachmentId)`, `deleteRecord(long)`, and field `archive`.

- [ ] **Step 1: Write the harness and the failing tests**

Create `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Moving, splitting and concatenating pages between records without re-running the pipeline.
 *
 * <p>Each record is built through the real ingest API and then "settled" the way a finished
 * pipeline leaves it: text, translation, search row, chunk, completed job and person match for
 * every page. The tests then check that all of that follows a page untouched.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageMoveTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;
  @Autowired private Path storageRoot;

  private String base;
  private long archive;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN_EMAIL = "page-move-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    base = "http://localhost:" + port + "/api";
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM record").update();

    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'PageMoveAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('PageMoveAdmin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();
    archive = newArchive();
  }

  // --- harness ----------------------------------------------------------------------------

  private long newArchive() {
    return jdbc.sql("INSERT INTO archive (name, country) VALUES (:n, 'AT') RETURNING id")
        .param("n", "Page Move Archive " + UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  private long newRecord(long archiveId) throws Exception {
    String body =
        """
        {"archiveId":%d,"sourceSystem":"test","sourceRecordId":"%s","lang":"de","metadataLang":"de"}
        """
            .formatted(archiveId, UUID.randomUUID());
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    return json.readTree(resp.body()).get("id").asLong();
  }

  private long uploadPage(long recordId, int seq, String content) throws Exception {
    String boundary = "----B" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"p.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .formatted(boundary)
            .getBytes(StandardCharsets.UTF_8));
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/pages?seq=" + seq))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    return json.readTree(resp.body()).get("id").asLong();
  }

  /** What a finished pipeline leaves behind for one page. */
  private void seedOutputs(long recordId, long pageId, String tag, int index) {
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, confidence, text_raw, text_en)"
                + " VALUES (:p, 'mistral', 0.9, :t, :e)")
        .param("p", pageId)
        .param("t", "Text " + tag)
        .param("e", "English " + tag)
        .update();
    jdbc.sql("INSERT INTO page_translation (page_id, model, text_en) VALUES (:p, 'test', :e)")
        .param("p", pageId)
        .param("e", "English " + tag)
        .update();
    jdbc.sql(
            "INSERT INTO page_search (page_id, best_engine, best_text_norm)"
                + " VALUES (:p, 'mistral', :t)")
        .param("p", pageId)
        .param("t", "text " + tag)
        .update();
    jdbc.sql(
            "INSERT INTO text_chunk (record_id, page_id, chunk_index, content)"
                + " VALUES (:r, :p, :i, :t)")
        .param("r", recordId)
        .param("p", pageId)
        .param("i", index)
        .param("t", "Text " + tag)
        .update();
    jdbc.sql(
            "INSERT INTO job (kind, record_id, page_id, status, finished_at)"
                + " VALUES ('ocr_page_mistral', :r, :p, 'completed', now())")
        .param("r", recordId)
        .param("p", pageId)
        .update();
    jdbc.sql(
            "INSERT INTO page_person_match (page_id, person_id, person_name, score)"
                + " VALUES (:p, 1, 'Test Person', 0.8)")
        .param("p", pageId)
        .update();
  }

  private long recordWithPages(String tag, int n) throws Exception {
    return recordWithPages(tag, n, archive);
  }

  /** A record of n pages, each with a finished pipeline's output, status complete. */
  private long recordWithPages(String tag, int n, long archiveId) throws Exception {
    long record = newRecord(archiveId);
    for (int seq = 1; seq <= n; seq++) {
      long page = uploadPage(record, seq, tag + "-page-" + seq);
      seedOutputs(record, page, tag + "-" + seq, seq);
    }
    jdbc.sql("UPDATE record SET status = 'complete' WHERE id = :r").param("r", record).update();
    return record;
  }

  private List<Long> pageIds(long recordId) {
    return jdbc.sql("SELECT id FROM page WHERE record_id = :r ORDER BY seq")
        .param("r", recordId)
        .query(Long.class)
        .list();
  }

  private List<Integer> seqs(long recordId) {
    return jdbc.sql("SELECT seq FROM page WHERE record_id = :r ORDER BY seq")
        .param("r", recordId)
        .query(Integer.class)
        .list();
  }

  private long attachmentOf(long pageId) {
    return one("SELECT attachment_id FROM page WHERE id = ?", pageId);
  }

  private String pathOf(long attachmentId) {
    return jdbc.sql("SELECT path FROM attachment WHERE id = :id")
        .param("id", attachmentId)
        .query(String.class)
        .single();
  }

  private long one(String sql, Object... params) {
    return jdbc.sql(sql).params(Arrays.asList(params)).query(Long.class).single();
  }

  private long count(String sql, Object... params) {
    return one(sql, params);
  }

  private HttpResponse<String> post(String path, String jsonBody) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(HttpResponse<String> resp) throws Exception {
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    return json.readTree(resp.body());
  }

  private void deleteRecord(long recordId) throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId))
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isIn(200, 204);
  }

  private String movePath(long recordId, long pageId) {
    return "/admin/records/" + recordId + "/pages/" + pageId + "/move";
  }

  // --- tests: moving one page --------------------------------------------------------------

  @Test
  void aMovedPageKeepsItsIdAndEverythingKeyedOnIt() throws Exception {
    long a = recordWithPages("a", 3);
    long b = recordWithPages("b", 2);
    List<Long> aPages = pageIds(a);
    long moving = aPages.get(1);
    long attachment = attachmentOf(moving);
    String pathBefore = pathOf(attachment);
    long textId = one("SELECT id FROM page_text WHERE page_id = ?", moving);
    long chunkId = one("SELECT id FROM text_chunk WHERE page_id = ?", moving);
    long jobId = one("SELECT id FROM job WHERE page_id = ?", moving);
    long jobsBefore = count("SELECT count(*) FROM job");

    JsonNode out = ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));

    assertThat(out.get("targetPageCount").asInt()).isEqualTo(3);
    assertThat(out.get("sourcePageCount").asInt()).isEqualTo(2);
    // the page is the same page, now in B at the end
    assertThat(one("SELECT record_id FROM page WHERE id = ?", moving)).isEqualTo(b);
    assertThat(one("SELECT seq FROM page WHERE id = ?", moving)).isEqualTo(3L);
    assertThat(one("SELECT attachment_id FROM page WHERE id = ?", moving)).isEqualTo(attachment);
    // its scan: same attachment, same address, now owned by B, and the file still opens
    assertThat(one("SELECT record_id FROM attachment WHERE id = ?", attachment)).isEqualTo(b);
    assertThat(pathOf(attachment)).isEqualTo(pathBefore);
    assertThat(Files.readString(storageRoot.resolve(pathBefore))).isEqualTo("a-page-2");
    // everything keyed on the page followed, with the same row ids
    assertThat(one("SELECT id FROM page_text WHERE page_id = ?", moving)).isEqualTo(textId);
    assertThat(count("SELECT count(*) FROM page_translation WHERE page_id = ?", moving)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_search WHERE page_id = ?", moving)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_person_match WHERE page_id = ?", moving))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_ocr_history WHERE page_id = ?", moving))
        .isEqualTo(1);
    // chunks and finished jobs are re-parented, not deleted
    assertThat(one("SELECT id FROM text_chunk WHERE page_id = ?", moving)).isEqualTo(chunkId);
    assertThat(one("SELECT record_id FROM text_chunk WHERE id = ?", chunkId)).isEqualTo(b);
    assertThat(one("SELECT id FROM job WHERE page_id = ?", moving)).isEqualTo(jobId);
    assertThat(one("SELECT record_id FROM job WHERE id = ?", jobId)).isEqualTo(b);
    // both records are contiguous, in the right order, and their counters are right
    assertThat(seqs(a)).containsExactly(1, 2);
    assertThat(pageIds(a)).containsExactly(aPages.get(0), aPages.get(2));
    assertThat(seqs(b)).containsExactly(1, 2, 3);
    assertThat(one("SELECT page_count FROM record WHERE id = ?", a)).isEqualTo(2);
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", a)).isEqualTo(2);
    assertThat(one("SELECT page_count FROM record WHERE id = ?", b)).isEqualTo(3);
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", b)).isEqualTo(3);
    // no pipeline work was created, and neither record left `complete`
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore);
    assertThat(count("SELECT count(*) FROM record WHERE id IN (?, ?) AND status = 'complete'", a, b))
        .isEqualTo(2);
  }

  @Test
  void aPageCanBeInsertedAtAGivenPositionInTheTarget() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 3);
    List<Long> bBefore = pageIds(b);
    long moving = pageIds(a).get(0);

    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + ",\"seq\":2}"));

    assertThat(seqs(b)).containsExactly(1, 2, 3, 4);
    assertThat(pageIds(b))
        .containsExactly(bBefore.get(0), moving, bBefore.get(1), bBefore.get(2));
  }

  @Test
  void aRoundTripLeavesEveryPageWhereItStartedWithItsIdIntact() throws Exception {
    long a = recordWithPages("a", 3);
    long b = recordWithPages("b", 2);
    List<Long> aBefore = pageIds(a);
    long moving = aBefore.get(1);

    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));
    ok(post(movePath(b, moving), "{\"targetRecordId\":" + a + ",\"seq\":2}"));

    assertThat(pageIds(a)).containsExactlyElementsOf(aBefore);
    assertThat(seqs(a)).containsExactly(1, 2, 3);
    assertThat(seqs(b)).containsExactly(1, 2);
    assertThat(one("SELECT page_count FROM record WHERE id = ?", a)).isEqualTo(3);
    assertThat(one("SELECT page_count FROM record WHERE id = ?", b)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM page_text WHERE page_id = ?", moving)).isEqualTo(1);
  }

  @Test
  void theMovedPagesScanSurvivesDeletingTheRecordItLeft() throws Exception {
    // The reason files are addressed by their own identity: deleting a record removes what the
    // record owns, and the moved page is no longer owned by it.
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long moving = pageIds(a).get(0);
    long attachment = attachmentOf(moving);
    Path file = storageRoot.resolve(pathOf(attachment));

    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));
    deleteRecord(a);

    assertThat(count("SELECT count(*) FROM page WHERE id = ?", moving)).isEqualTo(1);
    assertThat(one("SELECT record_id FROM page WHERE id = ?", moving)).isEqualTo(b);
    assertThat(Files.readString(file)).isEqualTo("a-page-1");
    assertThat(count("SELECT count(*) FROM page_text WHERE page_id = ?", moving)).isEqualTo(1);
  }

  @Test
  void aMoveIsWrittenToBothRecordsHistory() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long moving = pageIds(a).get(0);

    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));

    assertThat(count("SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'", a))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'", b))
        .isEqualTo(1);
  }

  @Test
  void aPageThatIsNotInTheNamedRecordIsNotFound() throws Exception {
    long a = recordWithPages("a", 1);
    long b = recordWithPages("b", 1);
    long other = pageIds(b).get(0);

    HttpResponse<String> resp = post(movePath(a, other), "{\"targetRecordId\":" + b + "}");

    assertThat(resp.statusCode()).isEqualTo(404);
    assertThat(pageIds(b)).containsExactly(other);
  }

  @Test
  void anOutOfRangeOrNonNumericPositionIsRefusedAndNothingChanges() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 2);
    long moving = pageIds(a).get(0);

    for (String body :
        List.of(
            "{\"targetRecordId\":" + b + ",\"seq\":0}",
            "{\"targetRecordId\":" + b + ",\"seq\":-1}",
            "{\"targetRecordId\":" + b + ",\"seq\":99}",
            "{\"targetRecordId\":" + b + ",\"seq\":\"two\"}",
            "{\"targetRecordId\":\"b\"}",
            "{}")) {
      assertThat(post(movePath(a, moving), body).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(seqs(a)).containsExactly(1, 2);
    assertThat(seqs(b)).containsExactly(1, 2);
  }

  @Test
  void anUnknownTargetRecordIsNotFound() throws Exception {
    long a = recordWithPages("a", 1);
    long moving = pageIds(a).get(0);

    assertThat(post(movePath(a, moving), "{\"targetRecordId\":99999999}").statusCode())
        .isEqualTo(404);
    assertThat(pageIds(a)).containsExactly(moving);
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: every test FAILS with a 404/405 from the missing endpoint (the harness itself compiles and the setup succeeds).

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V18__pipeline_event_pages_moved.sql`:

```sql
-- pipeline_event.event names what happened to a record's pipeline. Moving pages between records
-- is not a stage running, but it changes what the record holds, and it belongs in the same history.
ALTER TABLE pipeline_event DROP CONSTRAINT pipeline_event_event_check;
ALTER TABLE pipeline_event ADD CONSTRAINT pipeline_event_event_check
  CHECK (event = ANY (ARRAY['started', 'completed', 'failed', 'admin_reset', 'replace_started',
                            'repair_started', 'pages_moved']));
```

- [ ] **Step 4: Extract `PageSequence` and make `IngestService` use it**

Create `backend/src/main/java/place/icomb/archiver/service/PageSequence.java`:

```java
package place.icomb.archiver.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** Renumbering of a record's pages — the one place it is done. */
final class PageSequence {

  private static final int PARKING_SPACE = 1_000_000;

  private PageSequence() {}

  /**
   * Moves every page from {@code fromSeq} upward by {@code delta}.
   *
   * <p>In two steps through a high offset: (record_id, seq) is unique and not deferrable, so
   * shifting in place collides with the row being moved into.
   */
  static void shift(JdbcTemplate jdbc, Long recordId, int fromSeq, int delta) {
    jdbc.update(
        "UPDATE page SET seq = seq + ? WHERE record_id = ? AND seq >= ?",
        PARKING_SPACE,
        recordId,
        fromSeq);
    jdbc.update(
        "UPDATE page SET seq = seq - ? + ? WHERE record_id = ? AND seq >= ?",
        PARKING_SPACE,
        delta,
        recordId,
        PARKING_SPACE);
  }
}
```

In `IngestService.java`, replace the whole body of `shiftSeq` (keep its Javadoc and signature) so it reads:

```java
  private void shiftSeq(Long recordId, int fromSeq, int delta) {
    PageSequence.shift(jdbcTemplate, recordId, fromSeq, delta);
  }
```

- [ ] **Step 5: Create the exception**

Create `backend/src/main/java/place/icomb/archiver/service/PageMoveException.java`:

```java
package place.icomb.archiver.service;

/** A move that was refused. The kind says which HTTP status it deserves. */
public class PageMoveException extends RuntimeException {

  public enum Kind {
    NOT_FOUND,
    BAD_REQUEST,
    CONFLICT
  }

  private final Kind kind;

  public PageMoveException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
```

- [ ] **Step 6: Create the service**

Create `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java`:

```java
package place.icomb.archiver.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.service.PageMoveException.Kind;

/**
 * Moves pages between records without running any of the pipeline again.
 *
 * <p>A page's scan lives at an address that says nothing about the record it is in, so a move is a
 * database statement: the page, its attachment, its chunks and its finished jobs change record;
 * everything keyed on the page id — text, translation, search row, OCR history, person matches —
 * follows because the page keeps its id.
 */
@Service
public class PageMoveService {

  private final JdbcTemplate jdbc;
  private final RecordEventService recordEventService;

  public PageMoveService(JdbcTemplate jdbc, RecordEventService recordEventService) {
    this.jdbc = jdbc;
    this.recordEventService = recordEventService;
  }

  /** What a move did. {@code pdfRebuildRecordIds} names the records whose PDF was re-queued. */
  public record Moved(
      List<Long> pageIds,
      long sourceRecordId,
      int sourcePageCount,
      long targetRecordId,
      int targetPageCount,
      List<Long> pdfRebuildRecordIds) {}

  /** The columns of a record that decide whether pages may move in or out of it. */
  private record Locked(long id, long archiveId, String status, boolean held) {}

  /**
   * Moves one page to another record.
   *
   * @param atSeq where it lands in the target (1..pages+1); null appends it
   */
  @Transactional
  public Moved movePage(
      long sourceRecordId,
      long pageId,
      long targetRecordId,
      Integer atSeq,
      boolean allowCrossArchive) {
    lockRecords(sourceRecordId, targetRecordId);
    Integer seq =
        jdbc
            .queryForList(
                "SELECT seq FROM page WHERE id = ? AND record_id = ?",
                Integer.class,
                pageId,
                sourceRecordId)
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new PageMoveException(
                        Kind.NOT_FOUND,
                        "Page %d is not in record %d".formatted(pageId, sourceRecordId)));
    int targetPages = pageCount(targetRecordId);
    int at = atSeq == null ? targetPages + 1 : atSeq;
    if (at < 1 || at > targetPages + 1) {
      throw new PageMoveException(
          Kind.BAD_REQUEST,
          "seq %d is outside 1..%d for record %d".formatted(at, targetPages + 1, targetRecordId));
    }
    return moveRun(sourceRecordId, seq, seq, targetRecordId, at);
  }

  /**
   * Moves the run of pages {@code fromSeq..toSeq} of {@code src} into {@code tgt} starting at
   * {@code atSeq}, closing the gap it leaves. Callers have already locked both records.
   */
  private Moved moveRun(long src, int fromSeq, int toSeq, long tgt, int atSeq) {
    int n = toSeq - fromSeq + 1;
    int lastAt = atSeq + n - 1;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ? ORDER BY seq",
            Long.class,
            src,
            fromSeq,
            toSeq);

    // Open a gap in the target, drop the run into it, then close the gap it left in the source.
    PageSequence.shift(jdbc, tgt, atSeq, n);
    jdbc.update(
        "UPDATE page SET record_id = ?, seq = seq - ? + ? WHERE record_id = ? AND seq BETWEEN ? AND ?",
        tgt,
        fromSeq,
        atSeq,
        src,
        fromSeq,
        toSeq);

    // The pages now sit at atSeq..lastAt in the target. Their attachments must follow, or deleting
    // the source record would cascade away the scans and, through them, the pages.
    jdbc.update(
        "UPDATE attachment SET record_id = ? WHERE id IN"
            + " (SELECT attachment_id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ?)",
        tgt,
        tgt,
        atSeq,
        lastAt);
    String moved = "(SELECT id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ?)";
    jdbc.update(
        "UPDATE text_chunk SET record_id = ? WHERE page_id IN " + moved, tgt, tgt, atSeq, lastAt);
    jdbc.update("UPDATE job SET record_id = ? WHERE page_id IN " + moved, tgt, tgt, atSeq, lastAt);

    PageSequence.shift(jdbc, src, toSeq + 1, -n);

    refreshCounts(src);
    refreshCounts(tgt);
    logMove(src, "moved out %s to record %d".formatted(ids, tgt));
    logMove(tgt, "received %s from record %d at seq %d".formatted(ids, src, atSeq));
    recordEventService.recordChanged(src, "updated");
    recordEventService.recordChanged(tgt, "updated");

    return new Moved(ids, src, pageCount(src), tgt, pageCount(tgt), List.of());
  }

  /** Locks both records in id order, so two moves crossing in opposite directions cannot deadlock. */
  private Map<Long, Locked> lockRecords(long a, long b) {
    Map<Long, Locked> found = new HashMap<>();
    jdbc.query(
        "SELECT id, archive_id, status, ai_held_at IS NOT NULL AS held FROM record"
            + " WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
        rs -> {
          found.put(
              rs.getLong("id"),
              new Locked(
                  rs.getLong("id"),
                  rs.getLong("archive_id"),
                  rs.getString("status"),
                  rs.getBoolean("held")));
        },
        a,
        b);
    for (long id : new long[] {a, b}) {
      if (!found.containsKey(id)) {
        throw new PageMoveException(Kind.NOT_FOUND, "Record %d not found".formatted(id));
      }
    }
    return found;
  }

  private int pageCount(long recordId) {
    Integer n =
        jdbc.queryForObject("SELECT count(*) FROM page WHERE record_id = ?", Integer.class, recordId);
    return n == null ? 0 : n;
  }

  private void refreshCounts(long recordId) {
    jdbc.update(
        """
        UPDATE record SET
          page_count = (SELECT count(*) FROM page WHERE record_id = record.id),
          attachment_count = (SELECT count(*) FROM attachment WHERE record_id = record.id),
          updated_at = now()
        WHERE id = ?
        """,
        recordId);
  }

  private void logMove(long recordId, String detail) {
    jdbc.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)"
            + " VALUES (?, 'page_move', 'pages_moved', ?, now())",
        recordId,
        detail);
  }
}
```

- [ ] **Step 7: Create the controller**

Create `backend/src/main/java/place/icomb/archiver/controller/AdminPageMoveController.java`:

```java
package place.icomb.archiver.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.PageMoveException;
import place.icomb.archiver.service.PageMoveService;

/**
 * Moves pages between records. Under {@code /api/admin} because it rearranges an archive.
 *
 * <p>The page is named by its id, not its position: ids are stable, positions shift as earlier
 * pages move, and a caller iterating over positions would move the wrong pages.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPageMoveController {

  private final PageMoveService moves;

  public AdminPageMoveController(PageMoveService moves) {
    this.moves = moves;
  }

  @PostMapping("/records/{recordId}/pages/{pageId}/move")
  public ResponseEntity<Map<String, Object>> movePage(
      @PathVariable long recordId,
      @PathVariable long pageId,
      @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      long target = requiredLong(in, "targetRecordId");
      Integer seq = in.containsKey("seq") ? (int) requiredLong(in, "seq") : null;
      boolean cross = flag(in, "allowCrossArchive");
      return ResponseEntity.ok(describe(moves.movePage(recordId, pageId, target, seq, cross)));
    } catch (PageMoveException e) {
      return refuse(e);
    }
  }

  static Map<String, Object> describe(PageMoveService.Moved m) {
    Map<String, Object> out = new HashMap<>();
    out.put("pageIds", m.pageIds());
    out.put("sourceRecordId", m.sourceRecordId());
    out.put("sourcePageCount", m.sourcePageCount());
    out.put("targetRecordId", m.targetRecordId());
    out.put("targetPageCount", m.targetPageCount());
    out.put("pdfRebuildRecordIds", m.pdfRebuildRecordIds());
    return out;
  }

  static ResponseEntity<Map<String, Object>> refuse(PageMoveException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  static long requiredLong(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (!(v instanceof Number n)) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " is required and must be a number");
    }
    return n.longValue();
  }

  static boolean flag(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return false;
    }
    if (!(v instanceof Boolean b)) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " must be true or false");
    }
    return b;
  }
}
```

- [ ] **Step 8: Run the tests and the existing page-editing tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PageMoveTest' --tests '*ReplacePageAndRecordTest' --tests '*StorageMigrationTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. `ReplacePageAndRecordTest` passing proves the `shiftSeq` extraction changed nothing.

- [ ] **Step 9: Commit**

```bash
git add backend/src docs
git commit -m "pages: move one page to another record without running the pipeline

The page keeps its id, so its text, translation, search row, OCR history and person matches
follow it untouched. Its attachment, chunks and finished jobs are re-parented rather than
deleted — an attachment left behind would cascade the page away with the record it left.
Renumbering is shared with IngestService through PageSequence."
```

---

### Task 2: Refuse a move that would be wrong

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java` (`movePage`, add `requireMovable`)
- Test: `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java` (append tests)

**Interfaces:**
- Consumes: `lockRecords` returning `Map<Long, Locked>`, `moveRun`, `PageMoveException` (Task 1).
- Produces: private `requireMovable(Map<Long, Locked> locked, long src, long tgt, boolean allowCross, int fromSeq, int toSeq)` — throws `PageMoveException`; called by `movePage` here, and by `split` and `concat` in Tasks 4–5.

- [ ] **Step 1: Write the failing tests**

Append these tests inside the `PageMoveTest` class (before its final `}`):

```java
  // --- tests: refusals ---------------------------------------------------------------------

  /** Asserts a refused move changed nothing. */
  private void assertUntouched(long a, long b, List<Long> aPages, List<Long> bPages) {
    assertThat(pageIds(a)).containsExactlyElementsOf(aPages);
    assertThat(pageIds(b)).containsExactlyElementsOf(bPages);
    assertThat(count("SELECT count(*) FROM pipeline_event WHERE event = 'pages_moved'")).isZero();
  }

  private void refused(long a, long b, String extraBody, String setup, int status)
      throws Exception {
    List<Long> aPages = pageIds(a);
    List<Long> bPages = pageIds(b);
    if (setup != null) {
      jdbc.sql(setup).update();
    }
    HttpResponse<String> resp =
        post(movePath(a, aPages.get(0)), "{\"targetRecordId\":" + b + extraBody + "}");
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
    assertUntouched(a, b, aPages, bPages);
  }

  @Test
  void aRecordCannotBeMovedIntoItself() throws Exception {
    long a = recordWithPages("a", 2);
    refused(a, a, "", null, 400);
  }

  @Test
  void aRecordStillInThePipelineIsRefused() throws Exception {
    // The state machine advances a record whenever one of its jobs completes, and a move queues
    // one. On a record that is not complete that could restart paid work.
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(a, b, "", "UPDATE record SET status = 'translating' WHERE id = " + a, 409);
  }

  @Test
  void theTargetMustBeCompleteToo() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(a, b, "", "UPDATE record SET status = 'embedding' WHERE id = " + b, 409);
  }

  @Test
  void aRecordOnAiHoldIsRefused() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(a, b, "", "UPDATE record SET ai_held_at = now() WHERE id = " + b, 409);
  }

  @Test
  void aPendingJobAgainstEitherRecordIsRefused() throws Exception {
    // Its output would be written into the record the page has just left.
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(
        a,
        b,
        "",
        "INSERT INTO job (kind, record_id, status) VALUES ('translate_record', " + b + ", 'pending')",
        409);
  }

  @Test
  void aClaimedJobAgainstThePageItselfIsRefused() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long page = pageIds(a).get(0);
    refused(
        a,
        b,
        "",
        "INSERT INTO job (kind, record_id, page_id, status) VALUES ('translate_page', "
            + a
            + ", "
            + page
            + ", 'claimed')",
        409);
  }

  @Test
  void movingBetweenArchivesNeedsToBeAskedFor() throws Exception {
    long other = newArchive();
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1, other);
    refused(a, b, "", null, 409);

    long moving = pageIds(a).get(0);
    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + ",\"allowCrossArchive\":true}"));
    assertThat(one("SELECT record_id FROM page WHERE id = ?", moving)).isEqualTo(b);
  }

  @Test
  void aPageWhoseImageIsStillInTheLegacyLayoutIsRefused() throws Exception {
    // Emptying the source and then deleting it would remove a file under records/{id}/. Production
    // is migrated; a test stack is not.
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long attachment = attachmentOf(pageIds(a).get(0));
    refused(
        a,
        b,
        "",
        "UPDATE attachment SET path = 'records/1/attachments/pages/p0001.jpg' WHERE id = "
            + attachment,
        409);
  }

  @Test
  void aBooleanFlagThatIsNotABooleanIsRefused() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(a, b, ",\"allowCrossArchive\":\"yes\"", null, 400);
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: the nine new tests FAIL (moves that should be refused return `200`); the Task 1 tests still pass.

- [ ] **Step 3: Add `requireMovable` and call it**

In `PageMoveService.java`, change `movePage` so it keeps the returned map and calls the check after the page lookup. Replace this block:

```java
    lockRecords(sourceRecordId, targetRecordId);
    Integer seq =
```

with:

```java
    Map<Long, Locked> locked = lockRecords(sourceRecordId, targetRecordId);
    Integer seq =
```

and insert, between the `seq` lookup and `int targetPages = ...`:

```java
    requireMovable(locked, sourceRecordId, targetRecordId, allowCrossArchive, seq, seq);
```

Then add this method to the class:

```java
  /**
   * Refuses a move that would be wrong, before anything is touched.
   *
   * <p>Both records must be complete: {@code PipelineStateMachine.autoAdvance} runs whenever any
   * job of a record finishes, and a move queues a PDF rebuild. From {@code complete} there is
   * nowhere to advance to; from anywhere else it could restart paid work.
   */
  private void requireMovable(
      Map<Long, Locked> locked,
      long src,
      long tgt,
      boolean allowCross,
      int fromSeq,
      int toSeq) {
    if (src == tgt) {
      throw new PageMoveException(Kind.BAD_REQUEST, "Source and target are the same record");
    }
    Locked s = locked.get(src);
    Locked t = locked.get(tgt);
    for (Locked r : List.of(s, t)) {
      if (!"complete".equals(r.status())) {
        throw new PageMoveException(
            Kind.CONFLICT,
            "Record %d is %s, not complete; let its pipeline settle before moving pages"
                .formatted(r.id(), r.status()));
      }
      if (r.held()) {
        throw new PageMoveException(Kind.CONFLICT, "Record %d is on AI hold".formatted(r.id()));
      }
    }
    if (s.archiveId() != t.archiveId() && !allowCross) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "Records %d and %d are in different archives; pass allowCrossArchive to move between them"
              .formatted(src, tgt));
    }
    Long busy =
        jdbc.queryForObject(
            "SELECT count(*) FROM job WHERE record_id IN (?, ?) AND status IN ('pending', 'claimed')",
            Long.class,
            src,
            tgt);
    if (busy != null && busy > 0) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "%d job(s) are still pending or running against these records; a page moved now would"
                  .formatted(busy)
              + " have its output written into the record it left");
    }
    Long legacy =
        jdbc.queryForObject(
            "SELECT count(*) FROM page p JOIN attachment a ON a.id = p.attachment_id"
                + " WHERE p.record_id = ? AND p.seq BETWEEN ? AND ?"
                + " AND a.path NOT LIKE 'attachments/%'",
            Long.class,
            src,
            fromSeq,
            toSeq);
    if (legacy != null && legacy > 0) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "%d of these pages still have their image in the old records/{id}/ layout; run the storage"
                  .formatted(legacy)
              + " migration first, or deleting the record they leave would delete the scan");
    }
  }
```

- [ ] **Step 4: Run all page tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`, all Task 1 and Task 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "pages: refuse a move that is in the wrong state

Both records must be complete and off AI hold, with no job pending or running against either,
and the page's image must already be at an attachment address. A record still mid-pipeline
could restart paid work when the PDF rebuild completes; a legacy-layout image would be
deleted with the record the page left."
```

---

### Task 3: Invalidate and rebuild the searchable PDF, and relink it

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/StorageService.java` (add `deleteAfterCommit`)
- Modify: `backend/src/main/java/place/icomb/archiver/service/IngestService.java` (`deleteFilesAfterCommit` delegates)
- Modify: `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java` (constructor, `moveRun`)
- Modify: `backend/src/main/java/place/icomb/archiver/service/SearchablePdfWorker.java` (relink)
- Test: `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java`
- Test: `backend/src/test/java/place/icomb/archiver/service/SearchablePdfRelinkTest.java` (create)

**Interfaces:**
- Consumes: `moveRun`, `Moved` (Task 1); `JobService.enqueueJob(String kind, Long recordId, Long pageId, String payload)` (existing).
- Produces:
  - `StorageService.deleteAfterCommit(List<String> relativePaths)` — public.
  - `PageMoveService(JdbcTemplate, RecordEventService, JobService, StorageService)` — the constructor changes; `Moved.pdfRebuildRecordIds` now holds the records re-queued.
  - private `refreshPdf(long recordId)` returning `boolean` (true if a rebuild was queued).

- [ ] **Step 1: Write the failing tests**

In `PageMoveTest`, add a helper next to the other helpers:

```java
  /** A stored searchable PDF for a record, as the pipeline leaves it: a file, a row, a link. */
  private void withSearchablePdf(long recordId) throws Exception {
    String path = "records/%d/derivatives/pdf/searchable.pdf".formatted(recordId);
    Files.createDirectories(storageRoot.resolve(path).getParent());
    Files.writeString(storageRoot.resolve(path), "pdf of " + recordId);
    long id =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path, mime)"
                    + " VALUES (:r, 'searchable_pdf', :p, 'application/pdf') RETURNING id")
            .param("r", recordId)
            .param("p", path)
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE record SET pdf_attachment_id = :a, attachment_count = attachment_count + 1"
            + " WHERE id = :r")
        .param("a", id)
        .param("r", recordId)
        .update();
  }
```

and append these tests:

```java
  // --- tests: the searchable PDF -----------------------------------------------------------

  @Test
  void bothRecordsPdfsAreDroppedAndOnlyThePdfBuildIsQueued() throws Exception {
    // A PDF that still contains a page the record no longer owns is the kind of wrongness that
    // reaches a submission. And the only job a move may create is the PDF build.
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    withSearchablePdf(a);
    withSearchablePdf(b);
    Path aPdf = storageRoot.resolve("records/%d/derivatives/pdf/searchable.pdf".formatted(a));
    Path bPdf = storageRoot.resolve("records/%d/derivatives/pdf/searchable.pdf".formatted(b));
    long jobsBefore = count("SELECT count(*) FROM job");
    long moving = pageIds(a).get(0);

    JsonNode out = ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));

    assertThat(out.get("pdfRebuildRecordIds")).hasSize(2);
    assertThat(count("SELECT count(*) FROM attachment WHERE role = 'searchable_pdf'")).isZero();
    assertThat(count("SELECT count(*) FROM record WHERE id IN (?, ?) AND pdf_attachment_id IS NULL", a, b))
        .isEqualTo(2);
    assertThat(aPdf).doesNotExist();
    assertThat(bPdf).doesNotExist();
    // exactly two new jobs, both PDF builds, both pending
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore + 2);
    assertThat(
            count(
                "SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf' AND status = 'pending'"
                    + " AND record_id IN (?, ?)",
                a,
                b))
        .isEqualTo(2);
    // the counter followed the dropped row
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", a)).isEqualTo(1);
  }

  @Test
  void aRecordEmptiedByAMoveGetsNoPdfBuildAndItsPdfIsRemoved() throws Exception {
    long a = recordWithPages("a", 1);
    long b = recordWithPages("b", 1);
    withSearchablePdf(a);
    long moving = pageIds(a).get(0);

    JsonNode out = ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));

    assertThat(out.get("sourcePageCount").asInt()).isZero();
    assertThat(count("SELECT count(*) FROM record WHERE id = ?", a)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM attachment WHERE record_id = ? AND role = 'searchable_pdf'", a))
        .isZero();
    assertThat(count("SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf' AND record_id = ?", a))
        .isZero();
    assertThat(count("SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf' AND record_id = ?", b))
        .isEqualTo(1);
    assertThat(out.get("pdfRebuildRecordIds")).hasSize(1);
  }

  @Test
  void aRecordWithNoPdfStillGetsOneQueuedWhenItHasPages() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long moving = pageIds(a).get(0);

    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + "}"));

    assertThat(count("SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf'")).isEqualTo(2);
  }

  @Test
  void theOriginalPdfIsLeftAloneBecauseItIsTheDeliveredEvidence() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long original =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path) VALUES (:r, 'original_pdf',"
                    + " 'records/1/attachments/record.pdf') RETURNING id")
            .param("r", a)
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE record SET pdf_attachment_id = :o WHERE id = :r")
        .param("o", original)
        .param("r", a)
        .update();

    ok(post(movePath(a, pageIds(a).get(0)), "{\"targetRecordId\":" + b + "}"));

    assertThat(one("SELECT pdf_attachment_id FROM record WHERE id = ?", a)).isEqualTo(original);
    assertThat(count("SELECT count(*) FROM attachment WHERE id = ?", original)).isEqualTo(1);
  }
```

Create `backend/src/test/java/place/icomb/archiver/service/SearchablePdfRelinkTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.model.Job;

/**
 * A rebuilt searchable PDF has to be linked to its record. {@code /api/records/{id}/pdf} and the
 * viewer follow {@code record.pdf_attachment_id}, and until now only the pipeline's state machine
 * set it — so a rebuild on a record that was already complete left the new PDF orphaned.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class SearchablePdfRelinkTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @Autowired private JdbcClient jdbc;
  @Autowired private SearchablePdfWorker worker;
  @Autowired private Path storageRoot;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void clean() {
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM record").update();
  }

  private long recordWithOnePage() throws Exception {
    long archive =
        jdbc.sql("INSERT INTO archive (name, country) VALUES ('Relink', 'AT') RETURNING id")
            .query(Long.class)
            .single();
    long record =
        jdbc.sql(
                "INSERT INTO record (archive_id, source_system, source_record_id, status, lang)"
                    + " VALUES (:a, 'test', 'relink-' || gen_random_uuid(), 'complete', 'de')"
                    + " RETURNING id")
            .param("a", archive)
            .query(Long.class)
            .single();
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
    String path = "attachments/aa/relink-" + record + ".jpg";
    Files.createDirectories(storageRoot.resolve(path).getParent());
    Files.write(storageRoot.resolve(path), jpeg.toByteArray());
    long attachment =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path, mime)"
                    + " VALUES (:r, 'page_image', :p, 'image/jpeg') RETURNING id")
            .param("r", record)
            .param("p", path)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO page (record_id, seq, attachment_id, width, height) VALUES (:r, 1, :a, 40, 60)")
        .param("r", record)
        .param("a", attachment)
        .update();
    return record;
  }

  private void build(long record) throws Exception {
    Job job = new Job();
    job.setKind("build_searchable_pdf");
    job.setRecordId(record);
    worker.processJob(job);
  }

  @Test
  void aRebuildRelinksTheRecordWhenItHasNoPdfLinked() throws Exception {
    long record = recordWithOnePage();

    build(record);

    long pdf =
        jdbc.sql("SELECT id FROM attachment WHERE record_id = :r AND role = 'searchable_pdf'")
            .param("r", record)
            .query(Long.class)
            .single();
    long linked =
        jdbc.sql("SELECT pdf_attachment_id FROM record WHERE id = :r")
            .param("r", record)
            .query(Long.class)
            .single();
    assertThat(linked).isEqualTo(pdf);
  }

  @Test
  void aRebuildNeverStealsTheLinkFromTheOriginalPdf() throws Exception {
    // A record still mid-pipeline points at the PDF it was delivered as; the state machine swaps
    // that for the searchable one at the right moment. The worker must not pre-empt it.
    long record = recordWithOnePage();
    long original =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path) VALUES (:r, 'original_pdf',"
                    + " 'records/1/attachments/record.pdf') RETURNING id")
            .param("r", record)
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE record SET pdf_attachment_id = :o WHERE id = :r")
        .param("o", original)
        .param("r", record)
        .update();

    build(record);

    long linked =
        jdbc.sql("SELECT pdf_attachment_id FROM record WHERE id = :r")
            .param("r", record)
            .query(Long.class)
            .single();
    assertThat(linked).isEqualTo(original);
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PageMoveTest' --tests '*SearchablePdfRelinkTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: the four PDF tests in `PageMoveTest` FAIL (no jobs queued, PDFs untouched) and `aRebuildRelinksTheRecordWhenItHasNoPdfLinked` FAILS (`linked` is null).

- [ ] **Step 3: Move the after-commit delete into `StorageService`**

In `StorageService.java`, add this method after `deleteStoredFile`:

```java
  /**
   * Removes stored files after the surrounding transaction commits, so a rollback never leaves a
   * row pointing at a file that has gone. With no transaction, removes them at once.
   */
  public void deleteAfterCommit(java.util.List<String> relativePaths) {
    if (relativePaths.isEmpty()) {
      return;
    }
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      relativePaths.forEach(this::deleteStoredFile);
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                relativePaths.forEach(StorageService.this::deleteStoredFile);
              }
            });
  }
```

In `IngestService.java`, replace the whole body of `deleteFilesAfterCommit` (keep its Javadoc and signature) so it reads:

```java
  private void deleteFilesAfterCommit(java.util.List<String> paths) {
    storageService.deleteAfterCommit(paths);
  }
```

- [ ] **Step 4: Add the PDF handling to the service**

In `PageMoveService.java` change the fields and constructor:

```java
  private final JdbcTemplate jdbc;
  private final RecordEventService recordEventService;
  private final JobService jobService;
  private final StorageService storageService;

  public PageMoveService(
      JdbcTemplate jdbc,
      RecordEventService recordEventService,
      JobService jobService,
      StorageService storageService) {
    this.jdbc = jdbc;
    this.recordEventService = recordEventService;
    this.jobService = jobService;
    this.storageService = storageService;
  }
```

In `moveRun`, replace the last line (`return new Moved(ids, src, pageCount(src), tgt, pageCount(tgt), List.of());`) with:

```java
    // The stored searchable PDF is the one thing that is rebuilt: it is a local render of text
    // already held, and one that still contains a page the record no longer owns is wrong.
    List<Long> rebuilt = new java.util.ArrayList<>();
    for (long recordId : new long[] {src, tgt}) {
      if (refreshPdf(recordId)) {
        rebuilt.add(recordId);
      }
    }
    return new Moved(ids, src, pageCount(src), tgt, pageCount(tgt), rebuilt);
```

and add this method (after `moveRun`); the counters were refreshed earlier, so refresh them again after the row is dropped by moving the `refreshCounts` calls below — do that by deleting the two `refreshCounts(...)` lines from `moveRun` and calling them inside `refreshPdf` once the PDF row is gone. Concretely, remove from `moveRun`:

```java
    refreshCounts(src);
    refreshCounts(tgt);
```

and add:

```java
  /**
   * Drops a record's searchable PDF and queues a new one if it still has pages.
   *
   * <p>The row and its file go, the record's link is cleared only if it pointed at that PDF (a
   * record still holding its delivered original keeps that link), and a build is queued unless the
   * record is now empty. Counters are refreshed here, after the row is gone.
   *
   * @return true if a rebuild was queued
   */
  private boolean refreshPdf(long recordId) {
    List<String> paths =
        jdbc.queryForList(
            "SELECT path FROM attachment WHERE record_id = ? AND role = 'searchable_pdf'",
            String.class,
            recordId);
    jdbc.update(
        "UPDATE record SET pdf_attachment_id = NULL WHERE id = ? AND pdf_attachment_id IN"
            + " (SELECT id FROM attachment WHERE record_id = ? AND role = 'searchable_pdf')",
        recordId,
        recordId);
    jdbc.update(
        "DELETE FROM attachment WHERE record_id = ? AND role = 'searchable_pdf'", recordId);
    storageService.deleteAfterCommit(paths);
    refreshCounts(recordId);

    if (pageCount(recordId) == 0) {
      return false;
    }
    jobService.enqueueJob("build_searchable_pdf", recordId, null, null);
    return true;
  }
```

- [ ] **Step 5: Relink in the worker**

In `SearchablePdfWorker.java`, find `attachmentRepository.save(attachment);` inside `processJob` and replace that single line with:

```java
      attachment = attachmentRepository.save(attachment);

      // Nothing else links a rebuilt PDF to its record once the record is past the pipeline, and
      // /api/records/{id}/pdf follows the link. Only an empty link is filled: a record still
      // holding its delivered original is relinked by the state machine at the right moment.
      jdbcTemplate.update(
          "UPDATE record SET pdf_attachment_id = ? WHERE id = ? AND pdf_attachment_id IS NULL",
          attachment.getId(),
          recordId);
```

If `SearchablePdfWorker` has no `JdbcTemplate` field, add `private final JdbcTemplate jdbcTemplate;` with a constructor parameter and assignment, following the way its other collaborators (`storageService`, `attachmentRepository`) are injected.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PageMoveTest' --tests '*SearchablePdfRelinkTest' --tests '*StorageMigrationTest' --tests '*ReplacePageAndRecordTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. The Task 1 test `aMovedPageKeepsItsIdAndEverythingKeyedOnIt` asserts `job` count unchanged — it will now fail because two PDF jobs are queued. Update that one assertion to the true behaviour:

```java
    // no pipeline work was created beyond the two PDF builds, and neither record left `complete`
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore + 2);
    assertThat(count("SELECT count(*) FROM job WHERE kind <> 'build_searchable_pdf' AND status = 'pending'"))
        .isZero();
```

and re-run the same command; expected `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "pages: rebuild the searchable PDF after a move, and relink it

A PDF that still contains a page its record no longer owns is dropped, its file deleted after
commit, and a build queued unless the record is now empty. The worker now relinks
record.pdf_attachment_id after a build when the link is empty: only the state machine ever set
it, so a rebuild on a complete record left the new PDF orphaned and the viewer without one.
The after-commit file delete moves from IngestService into StorageService to be shared."
```

---

### Task 4: Split a record in two

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java` (constructor gains `RecordRepository`; add `split`)
- Modify: `backend/src/main/java/place/icomb/archiver/controller/AdminPageMoveController.java` (add endpoint)
- Test: `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java`

**Interfaces:**
- Consumes: `lockRecords`, `requireMovable`, `moveRun`, `Moved` (Tasks 1–3); `RecordRepository.save(Record)`; `Record` setters (`setArchiveId`, `setSourceSystem`, `setSourceRecordId`, `setTitle`, `setDescription`, `setTitleEn`, `setDescriptionEn`, `setLang`, `setMetadataLang`, `setOcrEngine`, `setTranslationQuality`, `setReferenceCode`, `setStatus`, `setCreatedAt`, `setUpdatedAt`).
- Produces:
  - `PageMoveService.SplitResult(long newRecordId, Moved moved)`.
  - `PageMoveService.split(long recordId, int splitAtSeq, String title, String description, String titleEn, String descriptionEn)`.
  - HTTP: `POST /api/admin/records/{recordId}/split`, body `{"splitAtSeq": N, "title": "…", "description": optional, "titleEn": optional, "descriptionEn": optional}` → `200` with the move description plus `newRecordId`.

- [ ] **Step 1: Write the failing tests**

Append to `PageMoveTest`:

```java
  // --- tests: split ------------------------------------------------------------------------

  private String splitBody(int at, String title) {
    return "{\"splitAtSeq\":%d,\"title\":\"%s\"}".formatted(at, title);
  }

  @Test
  void splitMovesTheTailIntoANewRecordThatInheritsWhatItShould() throws Exception {
    long a = recordWithPages("a", 5);
    jdbc.sql(
            "UPDATE record SET lang = 'cs', metadata_lang = 'de', ocr_engine = 'ocr_page_transkribus',"
                + " translation_quality = 'best', reference_code = '114-3-17' WHERE id = :r")
        .param("r", a)
        .update();
    List<Long> pages = pageIds(a);
    long jobsBefore = count("SELECT count(*) FROM job WHERE kind <> 'build_searchable_pdf'");

    JsonNode out = ok(post("/admin/records/" + a + "/split", splitBody(4, "Second half")));

    long created = out.get("newRecordId").asLong();
    assertThat(created).isNotEqualTo(a);
    assertThat(pageIds(a)).containsExactly(pages.get(0), pages.get(1), pages.get(2));
    assertThat(pageIds(created)).containsExactly(pages.get(3), pages.get(4));
    assertThat(seqs(created)).containsExactly(1, 2);
    // the new record inherits what a split of one document should: it is not a new acquisition
    assertThat(one("SELECT archive_id FROM record WHERE id = ?", created)).isEqualTo(archive);
    var row =
        jdbc.sql(
                "SELECT lang, metadata_lang, ocr_engine, translation_quality, reference_code,"
                    + " title, status, page_count FROM record WHERE id = :r")
            .param("r", created)
            .query()
            .singleRow();
    assertThat(row.get("lang")).isEqualTo("cs");
    assertThat(row.get("metadata_lang")).isEqualTo("de");
    assertThat(row.get("ocr_engine")).isEqualTo("ocr_page_transkribus");
    assertThat(row.get("translation_quality")).isEqualTo("best");
    assertThat(row.get("reference_code")).isEqualTo("114-3-17");
    assertThat(row.get("title")).isEqualTo("Second half");
    assertThat(row.get("status")).isEqualTo("complete");
    assertThat(((Number) row.get("page_count")).intValue()).isEqualTo(2);
    // everything moved with its pages, and no AI work was queued
    assertThat(count("SELECT count(*) FROM page_text WHERE page_id IN (?, ?)", pages.get(3), pages.get(4)))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM text_chunk WHERE record_id = ?", created)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM job WHERE kind <> 'build_searchable_pdf'"))
        .isEqualTo(jobsBefore);
  }

  @Test
  void theEnglishTitleIsSuppliedNotGenerated() throws Exception {
    long a = recordWithPages("a", 3);

    JsonNode out =
        ok(
            post(
                "/admin/records/" + a + "/split",
                "{\"splitAtSeq\":2,\"title\":\"Zweite\",\"titleEn\":\"Second\","
                    + "\"descriptionEn\":\"The second part\"}"));

    long created = out.get("newRecordId").asLong();
    var row =
        jdbc.sql("SELECT title_en, description_en FROM record WHERE id = :r")
            .param("r", created)
            .query()
            .singleRow();
    assertThat(row.get("title_en")).isEqualTo("Second");
    assertThat(row.get("description_en")).isEqualTo("The second part");
  }

  @Test
  void aSplitIsRefusedAtThePointsThatWouldLeaveARecordEmptyAndCreatesNothing() throws Exception {
    long a = recordWithPages("a", 3);
    long recordsBefore = count("SELECT count(*) FROM record");

    for (String body :
        List.of(
            splitBody(1, "x"), // would empty the source
            splitBody(0, "x"),
            splitBody(-2, "x"),
            splitBody(4, "x"), // beyond the last page
            "{\"splitAtSeq\":2}", // no title
            "{\"splitAtSeq\":2,\"title\":\"   \"}",
            "{\"title\":\"x\"}", // no split point
            "{\"splitAtSeq\":\"two\",\"title\":\"x\"}")) {
      assertThat(post("/admin/records/" + a + "/split", body).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(count("SELECT count(*) FROM record")).isEqualTo(recordsBefore);
    assertThat(seqs(a)).containsExactly(1, 2, 3);
  }

  @Test
  void aSplitOfARecordThatCannotBeMovedCreatesNothing() throws Exception {
    long a = recordWithPages("a", 3);
    jdbc.sql("UPDATE record SET ai_held_at = now() WHERE id = :r").param("r", a).update();
    long recordsBefore = count("SELECT count(*) FROM record");

    assertThat(post("/admin/records/" + a + "/split", splitBody(2, "x")).statusCode()).isEqualTo(409);

    assertThat(count("SELECT count(*) FROM record")).isEqualTo(recordsBefore);
    assertThat(seqs(a)).containsExactly(1, 2, 3);
  }

  @Test
  void splittingAnUnknownRecordIsNotFound() throws Exception {
    assertThat(post("/admin/records/99999999/split", splitBody(2, "x")).statusCode()).isEqualTo(404);
  }

  @Test
  void bothHalvesOfASplitGetTheirPdfRebuilt() throws Exception {
    long a = recordWithPages("a", 4);
    withSearchablePdf(a);

    JsonNode out = ok(post("/admin/records/" + a + "/split", splitBody(3, "Second half")));

    assertThat(out.get("pdfRebuildRecordIds")).hasSize(2);
    assertThat(count("SELECT count(*) FROM attachment WHERE role = 'searchable_pdf'")).isZero();
    assertThat(count("SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf'")).isEqualTo(2);
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: the six split tests FAIL with `404`/`405` (no endpoint).

- [ ] **Step 3: Add `split` to the service**

In `PageMoveService.java`, add the import `import place.icomb.archiver.model.Record; import place.icomb.archiver.repository.RecordRepository; import java.time.Instant;`, add a `RecordRepository recordRepository` field and constructor parameter (last), and add:

```java
  /** A split: the record it made, and the move that filled it. */
  public record SplitResult(long newRecordId, Moved moved) {}

  /**
   * Splits a record in two: pages {@code splitAtSeq..end} move to a new record.
   *
   * <p>The new record inherits what a split of one document should — archive, languages, OCR
   * engine, translation quality, reference code — because it is one document recognised as two,
   * not a new acquisition. It is created {@code complete}: its pages already went through the
   * pipeline. Its English title is supplied, never generated, because generating it is an AI call.
   */
  @Transactional
  public SplitResult split(
      long recordId,
      int splitAtSeq,
      String title,
      String description,
      String titleEn,
      String descriptionEn) {
    if (title == null || title.isBlank()) {
      throw new PageMoveException(Kind.BAD_REQUEST, "title is required for the new record");
    }
    Record source =
        recordRepository
            .findById(recordId)
            .orElseThrow(
                () ->
                    new PageMoveException(
                        Kind.NOT_FOUND, "Record %d not found".formatted(recordId)));
    int pages = pageCount(recordId);
    if (splitAtSeq < 2 || splitAtSeq > pages) {
      throw new PageMoveException(
          Kind.BAD_REQUEST,
          "splitAtSeq must be from 2 to %d, so that both records keep at least one page"
              .formatted(pages));
    }
    long firstMoved =
        jdbc.queryForObject(
            "SELECT id FROM page WHERE record_id = ? AND seq = ?", Long.class, recordId, splitAtSeq);

    Record made = new Record();
    made.setArchiveId(source.getArchiveId());
    made.setSourceSystem(source.getSourceSystem());
    // Unique, and derived from the first page moved: a page moves once, so the id cannot recur.
    made.setSourceRecordId(source.getSourceRecordId() + "#split-" + firstMoved);
    made.setTitle(title);
    made.setDescription(description);
    made.setTitleEn(titleEn);
    made.setDescriptionEn(descriptionEn);
    made.setLang(source.getLang());
    made.setMetadataLang(source.getMetadataLang());
    made.setOcrEngine(source.getOcrEngine());
    made.setTranslationQuality(source.getTranslationQuality());
    made.setReferenceCode(source.getReferenceCode());
    made.setStatus("complete");
    made.setCreatedAt(Instant.now());
    made.setUpdatedAt(Instant.now());
    made = recordRepository.save(made);

    Map<Long, Locked> locked = lockRecords(recordId, made.getId());
    requireMovable(locked, recordId, made.getId(), false, splitAtSeq, pages);
    Moved moved = moveRun(recordId, splitAtSeq, pages, made.getId(), 1);
    return new SplitResult(made.getId(), moved);
  }
```

- [ ] **Step 4: Add the endpoint**

In `AdminPageMoveController.java` add the endpoint after `movePage`:

```java
  @PostMapping("/records/{recordId}/split")
  public ResponseEntity<Map<String, Object>> split(
      @PathVariable long recordId, @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      int at = (int) requiredLong(in, "splitAtSeq");
      var result =
          moves.split(
              recordId,
              at,
              text(in, "title"),
              text(in, "description"),
              text(in, "titleEn"),
              text(in, "descriptionEn"));
      Map<String, Object> out = describe(result.moved());
      out.put("newRecordId", result.newRecordId());
      return ResponseEntity.ok(out);
    } catch (PageMoveException e) {
      return refuse(e);
    }
  }

  static String text(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw new PageMoveException(PageMoveException.Kind.BAD_REQUEST, key + " must be text");
    }
    return s;
  }
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "pages: split a record in two

Pages from the split point to the end move to a new record that inherits archive, languages,
OCR engine, translation quality and reference code, created complete because its pages already
went through the pipeline. Its English title is supplied by the caller, not generated."
```

---

### Task 5: Concatenate two records

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PageMoveService.java` (add `concat`)
- Modify: `backend/src/main/java/place/icomb/archiver/controller/AdminPageMoveController.java` (add endpoint)
- Test: `backend/src/test/java/place/icomb/archiver/controller/PageMoveTest.java`

**Interfaces:**
- Consumes: `lockRecords`, `requireMovable`, `moveRun`, `pageCount`, `Moved` (Tasks 1–3).
- Produces:
  - `PageMoveService.concat(long targetRecordId, long sourceRecordId, boolean allowCrossArchive)` — moves every page of the source onto the end of the target.
  - HTTP: `POST /api/admin/records/{recordId}/concat`, body `{"sourceRecordId": N, "allowCrossArchive": optional bool}` → `200` with the move description. `recordId` is the surviving record.

- [ ] **Step 1: Write the failing tests**

Append to `PageMoveTest`:

```java
  // --- tests: concatenate ------------------------------------------------------------------

  private String concatPath(long target) {
    return "/admin/records/" + target + "/concat";
  }

  @Test
  void concatAppendsEverySourcePageAndLeavesTheSourceEmptyButPresent() throws Exception {
    long a = recordWithPages("a", 3);
    long b = recordWithPages("b", 2);
    List<Long> aPages = pageIds(a);
    List<Long> bPages = pageIds(b);
    long jobsBefore = count("SELECT count(*) FROM job WHERE kind <> 'build_searchable_pdf'");

    JsonNode out = ok(post(concatPath(a), "{\"sourceRecordId\":" + b + "}"));

    assertThat(out.get("targetPageCount").asInt()).isEqualTo(5);
    assertThat(out.get("sourcePageCount").asInt()).isZero();
    assertThat(pageIds(a))
        .containsExactly(aPages.get(0), aPages.get(1), aPages.get(2), bPages.get(0), bPages.get(1));
    assertThat(seqs(a)).containsExactly(1, 2, 3, 4, 5);
    // the survivor keeps its own metadata; the emptied record is left in place for the user to
    // delete deliberately
    assertThat(count("SELECT count(*) FROM record WHERE id = ?", b)).isEqualTo(1);
    assertThat(seqs(b)).isEmpty();
    assertThat(one("SELECT page_count FROM record WHERE id = ?", b)).isZero();
    assertThat(one("SELECT page_count FROM record WHERE id = ?", a)).isEqualTo(5);
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", a)).isEqualTo(5);
    // text, chunks and jobs all followed; nothing was queued but the PDF build
    assertThat(count("SELECT count(*) FROM text_chunk WHERE record_id = ?", a)).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM page_text WHERE page_id IN (?, ?)", bPages.get(0), bPages.get(1)))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM job WHERE kind <> 'build_searchable_pdf'"))
        .isEqualTo(jobsBefore);
    // only the survivor gets a PDF build: the emptied record has no pages to render
    assertThat(out.get("pdfRebuildRecordIds")).hasSize(1);
    assertThat(count("SELECT count(*) FROM job WHERE kind = 'build_searchable_pdf' AND record_id = ?", b))
        .isZero();
  }

  @Test
  void deletingTheEmptiedRecordAfterAConcatKeepsEveryScan() throws Exception {
    long a = recordWithPages("a", 1);
    long b = recordWithPages("b", 2);
    List<Long> bPages = pageIds(b);
    Path scan = storageRoot.resolve(pathOf(attachmentOf(bPages.get(0))));

    ok(post(concatPath(a), "{\"sourceRecordId\":" + b + "}"));
    deleteRecord(b);

    assertThat(pageIds(a)).contains(bPages.get(0), bPages.get(1));
    assertThat(Files.readString(scan)).isEqualTo("b-page-1");
  }

  @Test
  void concatRefusesARecordWithItselfAnEmptyRecordAndABadBodyAndChangesNothing() throws Exception {
    long a = recordWithPages("a", 2);
    long empty = recordWithPages("e", 0);
    List<Long> before = pageIds(a);

    assertThat(post(concatPath(a), "{\"sourceRecordId\":" + a + "}").statusCode()).isEqualTo(400);
    assertThat(post(concatPath(a), "{\"sourceRecordId\":" + empty + "}").statusCode()).isEqualTo(400);
    assertThat(post(concatPath(a), "{}").statusCode()).isEqualTo(400);
    assertThat(post(concatPath(a), "{\"sourceRecordId\":\"b\"}").statusCode()).isEqualTo(400);
    assertThat(post(concatPath(a), "{\"sourceRecordId\":99999999}").statusCode()).isEqualTo(404);
    assertThat(post(concatPath(99999999), "{\"sourceRecordId\":" + a + "}").statusCode())
        .isEqualTo(404);
    assertThat(pageIds(a)).containsExactlyElementsOf(before);
    assertThat(count("SELECT count(*) FROM pipeline_event WHERE event = 'pages_moved'")).isZero();
  }

  @Test
  void concatAppliesTheSameRefusalsAsAMove() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 2);
    jdbc.sql("UPDATE record SET ai_held_at = now() WHERE id = :r").param("r", b).update();

    assertThat(post(concatPath(a), "{\"sourceRecordId\":" + b + "}").statusCode()).isEqualTo(409);
    assertThat(seqs(b)).containsExactly(1, 2);
  }

  @Test
  void concatAcrossArchivesNeedsToBeAskedFor() throws Exception {
    long a = recordWithPages("a", 1);
    long b = recordWithPages("b", 1, newArchive());

    assertThat(post(concatPath(a), "{\"sourceRecordId\":" + b + "}").statusCode()).isEqualTo(409);
    ok(post(concatPath(a), "{\"sourceRecordId\":" + b + ",\"allowCrossArchive\":true}"));
    assertThat(seqs(a)).containsExactly(1, 2);
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: the five concat tests FAIL with `404`/`405`.

- [ ] **Step 3: Add `concat` to the service**

In `PageMoveService.java` add:

```java
  /**
   * Moves every page of {@code sourceRecordId} onto the end of {@code targetRecordId}.
   *
   * <p>The target keeps its own metadata. The emptied source is left in place: deleting a record is
   * a separate, deliberate call, never a side effect of moving its pages.
   */
  @Transactional
  public Moved concat(long targetRecordId, long sourceRecordId, boolean allowCrossArchive) {
    Map<Long, Locked> locked = lockRecords(sourceRecordId, targetRecordId);
    int sourcePages = pageCount(sourceRecordId);
    if (sourcePages == 0 && sourceRecordId != targetRecordId) {
      throw new PageMoveException(
          Kind.BAD_REQUEST, "Record %d has no pages to move".formatted(sourceRecordId));
    }
    requireMovable(
        locked, sourceRecordId, targetRecordId, allowCrossArchive, 1, Math.max(sourcePages, 1));
    return moveRun(
        sourceRecordId, 1, sourcePages, targetRecordId, pageCount(targetRecordId) + 1);
  }
```

- [ ] **Step 4: Add the endpoint**

In `AdminPageMoveController.java`, after `split`:

```java
  @PostMapping("/records/{recordId}/concat")
  public ResponseEntity<Map<String, Object>> concat(
      @PathVariable long recordId, @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      long source = requiredLong(in, "sourceRecordId");
      boolean cross = flag(in, "allowCrossArchive");
      return ResponseEntity.ok(describe(moves.concat(recordId, source, cross)));
    } catch (PageMoveException e) {
      return refuse(e);
    }
  }
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PageMoveTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. If `concatRefusesARecordWithItself…` fails on the self case, the `src == tgt` check in `requireMovable` should already produce `400`; confirm `lockRecords` returned one entry and the `sourcePages == 0 && sourceRecordId != targetRecordId` guard did not fire first.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "pages: concatenate two records

Every page of the source moves onto the end of the target, which keeps its own metadata. The
emptied source is left in place — deleting a record stays a separate, deliberate call — and is
given no PDF build, since it has no pages to render."
```

---

### Task 6: Document it, run every check, push to the test stack

**Files:**
- Modify: `.claude/skills/archiver-api/SKILL.md` (add the three endpoints)
- Modify: `docs/superpowers/specs/2026-09-29-page-move-split-concat-design.md` (status, decisions)
- Modify: `CHANGELOG.md` (release entry — added in Task 7's release commit, not here)

**Interfaces:**
- Consumes: the three endpoints from Tasks 1, 4, 5.

- [ ] **Step 1: Document the endpoints in the skill**

Read `.claude/skills/archiver-api/SKILL.md`, find the section that documents `DELETE /api/admin/records/{id}/pages/{seq}` and `…/insert` (search for `insert`), and add directly after it a section in the same table/prose style:

```markdown
### Moving pages between records

All admin-only, all one transaction, none of them re-run OCR, translation, embedding or matching.
A page is named by its **id**, never its position. Both records must be `complete`, off AI hold,
with no job pending or running against either, and the page image must already be at an
`attachments/…` address (`GET /api/admin/storage/migrate` reports `legacy: 0`).

| Call | Does |
|---|---|
| `POST /api/admin/records/{id}/pages/{pageId}/move` `{"targetRecordId":N,"seq":optional,"allowCrossArchive":optional}` | Moves one page; appended unless `seq` is given (1..pages+1) |
| `POST /api/admin/records/{id}/split` `{"splitAtSeq":N,"title":"…","description":opt,"titleEn":opt,"descriptionEn":opt}` | Pages `N..end` move to a new `complete` record inheriting archive, languages, OCR engine, quality, reference code. `N` is 2..pages. English title is supplied, not generated |
| `POST /api/admin/records/{id}/concat` `{"sourceRecordId":N,"allowCrossArchive":optional}` | Every page of the source onto the end of `{id}`. The emptied source is left in place |

Refusals: `404` unknown record or page; `400` a bad body, a position out of range, a record into
itself, a split that would empty a record; `409` a record not `complete`, on hold, busy, in another
archive, or holding a legacy-layout image. A refusal changes nothing. Both records' searchable PDFs
are dropped and re-queued (one is not queued for a record left empty); the response's
`pdfRebuildRecordIds` names them. The stored **original** PDF is never touched.
```

- [ ] **Step 2: Update the spec's status**

In the spec, change the `**Status:**` line to `**Status:** deliverables 1 and 2 shipped in v1.1.15; deliverable 3 specified here and implemented by the plan `docs/superpowers/plans/2026-09-29-page-move-split-concat.md`. Phase 2 below is current, plus the four decisions recorded in that plan (both records `complete`; legacy-layout images refused; a rebuilt PDF is relinked; a split's English title is supplied).`

- [ ] **Step 3: Run every backend check**

Run: `cd backend && ./gradlew spotlessApply && ./gradlew spotlessCheck test 2>&1 | grep -E "FAILED|BUILD"`
Then: `python3 - <<'PY'` reading `build/test-results/test/*.xml` and printing totals of tests, skipped, failures, errors.
Expected: `BUILD SUCCESSFUL`; **0 failures, 0 errors**; total is the previous 468 plus the new tests.

- [ ] **Step 4: Commit and push (untagged → `:test`)**

```bash
git add -A
git commit -m "docs: document moving pages between records, and mark the spec's status"
git push origin main
```

Expected: Jenkins builds `:test` and redeploys the test stack; **do not** run `jk run start`. Find and wait for the build: `jk run ls archiver | head -2`, then `jk run view archiver <n> --wait`; expect `Result: SUCCESS`.

---

### Task 7: Rehearse on the test stack, then release

**Files:**
- Modify: `CHANGELOG.md` (release entry)

**Interfaces:**
- Consumes: the deployed `:test` build; the test stack's catalogue (a copy of production with the real scans mounted read-only).

- [ ] **Step 1: Confirm the test stack is on the new build**

Run: `ssh zelkova 'curl -s http://localhost:8090/api/version'`
Expected: the commit of the Task 6 push.

- [ ] **Step 2: Migrate the test stack's storage so real pages are movable**

The test stack has only ~50 of its ~128,836 page images migrated and a legacy-layout image is refused. Pick a real pair of records and migrate just enough: run `POST http://localhost:8090/api/admin/storage/migrate` with `{"limit":5000}` repeatedly (from `zelkova`, with `Authorization: Bearer $atok` where `atok` is `ARCHIVER_ADMIN_TOKEN` from `docker inspect archiver-test-backend-test-1`) until `GET` reports `legacy: 0`, or choose two records whose pages are already at `attachments/…`. The test stack's source is the read-only mount, so this exercises the copy fallback and touches no real file.

- [ ] **Step 3: Rehearse each operation on real records**

On the test stack, against two real `complete` records, take a before/after snapshot each time via `GET /api/v1/documents/{id}` (page count, page ids, text lengths):
1. `move` one page from record 4037 into 4036; confirm page ids, text and translations unchanged, both `pdfRebuildRecordIds` returned, and `GET /api/files/{attachmentId}` still serves the same bytes.
2. `split` 4037 at a real boundary; confirm the new record's pages, inherited fields, and that the job table holds no OCR/translate/embed work (`GET /api/admin/jobs?recordId=…`).
3. `concat` the two halves back; confirm the original page order and counts are restored.
4. Delete the emptied record via `DELETE /api/ingest/records/{id}` and confirm every moved page's image still opens.
5. Wait for the two queued `build_searchable_pdf` jobs to finish; confirm `GET /api/records/{id}/pdf` serves a PDF and its page count matches.

Record what was checked. **Stop and report if any check fails; do not release.**

- [ ] **Step 4: Add the changelog entry and tag on its own commit**

Add above `## v1.1.15` in `CHANGELOG.md` a `## v1.1.16 — <date>` entry covering: move/split/concat endpoints and what they guarantee (no pipeline re-run, chunks re-parented not deleted, only a PDF rebuild queued); the preconditions and why (both records complete, hold, busy, legacy layout); that a rebuilt searchable PDF is now relinked to its record. Then:

```bash
git add CHANGELOG.md
git commit -m "release: v1.1.16 — move, split and concatenate pages between records"
git tag v1.1.16
git push origin main
git push origin v1.1.16
```

- [ ] **Step 5: Verify the release**

Find and wait for the release build (`jk run ls archiver | head -2`; `jk run view archiver <n> --wait`; expect `SUCCESS` and `Deploying to PRODUCTION (v1.1.16)` in `jk log`). Then on `zelkova`:
- `curl -s http://10.0.9.3:8080/api/version` → `v1.1.16` at the tag's commit.
- `curl -s -o /dev/null -w "%{http_code}" https://archive.czernin.eu/` → `200`.
- `docker ps -a --filter name=archiver-` → backend and frontend `Up`, nothing restarting.

Do **not** move any real page on production as part of the release check; boundary corrections on 4028–4038 are a separate, deliberate task.

---

## Self-Review

**Spec coverage** (Phase 2 of the spec and the conversation constraints):
- Move / split / concat as database updates → Tasks 1, 4, 5.
- Page keeps its id and everything keyed on it follows → Task 1 test.
- `text_chunk` re-parented, never deleted → Task 1 test (same chunk id, new `record_id`).
- `seq` contiguous, parking offset → Task 1 (`PageSequence.shift`), tests assert contiguity, round trip.
- Counters (`page_count`, `attachment_count`) → Task 1, refreshed after PDF row removal in Task 3.
- History event → Task 1 test + `V18`.
- Only `build_searchable_pdf` may be created, removed not rebuilt on an emptied record → Task 3 tests, Task 5 test.
- Record status unchanged → Task 1 test.
- Delete safety → Task 1 and Task 5 tests.
- Every refusal (held, busy job, unknown, page not in record, same record, cross-archive) → Task 2; plus split/concat shape refusals → Tasks 4, 5.
- Split inheritance → Task 4 test. Concat leaves source empty but present → Task 5 test.
- Test-stack proof before tagging → Task 7.
- Gaps found and closed by decisions: `attachment.record_id` must move (else cascade); PDF relink; `complete`-only precondition; legacy-layout refusal; English title supplied.

**Placeholder scan:** no `TBD`/`TODO`/"handle edge cases"; every code step carries the code. One conditional instruction remains — Task 3 Step 5 says to add a `JdbcTemplate` field to `SearchablePdfWorker` *if it has none*; the worker's constructor was not read in full, so the executor must look and follow the existing injection style (the code to add is given).

**Type consistency:** `Moved(pageIds, sourceRecordId, sourcePageCount, targetRecordId, targetPageCount, pdfRebuildRecordIds)` is defined in Task 1 and used unchanged in Tasks 3–5 and the controller `describe`. `movePage(long, long, long, Integer, boolean)` matches the controller call. `lockRecords` returns `Map<Long, Locked>` from Task 1 onward. `requireMovable(Map, long, long, boolean, int, int)` is defined in Task 2 and called with that signature in Tasks 4 and 5. `PageMoveService`'s constructor gains parameters in Task 3 (`JobService`, `StorageService`) and Task 4 (`RecordRepository`); Spring wires it by type, and no test constructs it directly.
