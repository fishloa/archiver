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
import place.icomb.archiver.service.PipelineAuditService;

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
  @Autowired private place.icomb.archiver.service.JobService jobService;

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
    assertThat(count("SELECT count(*) FROM page_translation WHERE page_id = ?", moving))
        .isEqualTo(1);
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
    assertThat(
            count("SELECT count(*) FROM record WHERE id IN (?, ?) AND status = 'complete'", a, b))
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
    assertThat(pageIds(b)).containsExactly(bBefore.get(0), moving, bBefore.get(1), bBefore.get(2));
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
  void theOnlyPageOfARecordCanMoveOutLeavingItEmptyAndInPlace() throws Exception {
    long a = recordWithPages("a", 1);
    long b = recordWithPages("b", 1);
    long jobsBefore = count("SELECT count(*) FROM job");

    JsonNode out = ok(post(movePath(a, pageIds(a).get(0)), "{\"targetRecordId\":" + b + "}"));

    assertThat(out.get("sourcePageCount").asInt()).isZero();
    assertThat(seqs(a)).isEmpty();
    assertThat(count("SELECT count(*) FROM record WHERE id = ?", a)).isEqualTo(1);
    assertThat(one("SELECT page_count FROM record WHERE id = ?", a)).isZero();
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", a)).isZero();
    assertThat(seqs(b)).containsExactly(1, 2);
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore);
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

    assertThat(
            count(
                "SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'",
                a))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'",
                b))
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

  // --- tests: refusals ---------------------------------------------------------------------

  /** What a refused move must leave exactly as it was. */
  private record Snapshot(
      String statusA,
      String statusB,
      long pageCountA,
      long pageCountB,
      List<Long> attachmentsA,
      List<Long> attachmentsB,
      long jobs) {}

  private List<Long> attachmentIds(long recordId) {
    return jdbc.sql("SELECT id FROM attachment WHERE record_id = :r ORDER BY id")
        .param("r", recordId)
        .query(Long.class)
        .list();
  }

  private String text(String sql, Object id) {
    return jdbc.sql(sql).param(id).query(String.class).single();
  }

  private String statusOf(long recordId) {
    return jdbc.sql("SELECT status FROM record WHERE id = :r")
        .param("r", recordId)
        .query(String.class)
        .single();
  }

  private Snapshot snapshot(long a, long b) {
    return new Snapshot(
        statusOf(a),
        statusOf(b),
        one("SELECT page_count FROM record WHERE id = ?", a),
        one("SELECT page_count FROM record WHERE id = ?", b),
        attachmentIds(a),
        attachmentIds(b),
        count("SELECT count(*) FROM job"));
  }

  /** Asserts a refused move changed nothing. */
  private void assertUntouched(
      long a, long b, List<Long> aPages, List<Long> bPages, Snapshot before) {
    assertThat(pageIds(a)).containsExactlyElementsOf(aPages);
    assertThat(pageIds(b)).containsExactlyElementsOf(bPages);
    assertThat(count("SELECT count(*) FROM pipeline_event WHERE event = 'pages_moved'")).isZero();
    assertThat(snapshot(a, b)).isEqualTo(before);
  }

  private void refused(long a, long b, String extraBody, String setup, int status)
      throws Exception {
    List<Long> aPages = pageIds(a);
    List<Long> bPages = pageIds(b);
    if (setup != null) {
      jdbc.sql(setup).update();
    }
    Snapshot before = snapshot(a, b);
    HttpResponse<String> resp =
        post(movePath(a, aPages.get(0)), "{\"targetRecordId\":" + b + extraBody + "}");
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
    assertUntouched(a, b, aPages, bPages, before);
  }

  @Test
  void aRecordCannotBeMovedIntoItself() throws Exception {
    long a = recordWithPages("a", 2);
    refused(a, a, "", null, 400);
  }

  @Test
  void aRecordStillInThePipelineIsRefused() throws Exception {
    // A record still in the pipeline re-evaluates its guards whenever one of its jobs completes,
    // and a move must not hand it pages it has not processed.
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
        "INSERT INTO job (kind, record_id, status) VALUES ('translate_record', "
            + b
            + ", 'pending')",
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
  void aPdfExportBeingPreparedForEitherRecordIsRefused() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(
        a,
        b,
        "",
        "INSERT INTO pdf_export (record_id, variant, page_ids, fingerprint, state) VALUES ("
            + b
            + ", 'original', ARRAY[]::bigint[], 'x', 'building')",
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

  @Test
  void aClaimedJobAgainstEitherRecordIsRefused() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    refused(
        a,
        b,
        "",
        "INSERT INTO job (kind, record_id, status) VALUES ('translate_record', "
            + a
            + ", 'claimed')",
        409);
  }

  @Test
  void aPositionOutsideTheIntRangeOrFractionalIsRefusedNotWrapped() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 2);
    long moving = pageIds(a).get(0);
    for (String seq : List.of("4294967297", "1.5", "99999999999999999999")) {
      HttpResponse<String> resp =
          post(movePath(a, moving), "{\"targetRecordId\":" + b + ",\"seq\":" + seq + "}");
      assertThat(resp.statusCode()).as(seq).isEqualTo(400);
    }
    assertThat(seqs(a)).containsExactly(1, 2);
    assertThat(seqs(b)).containsExactly(1, 2);
  }

  @Test
  void anExplicitNullPositionAppends() throws Exception {
    long a = recordWithPages("a", 2);
    long b = recordWithPages("b", 1);
    long moving = pageIds(a).get(0);
    ok(post(movePath(a, moving), "{\"targetRecordId\":" + b + ",\"seq\":null}"));
    assertThat(pageIds(b)).endsWith(moving);
    assertThat(seqs(b)).containsExactly(1, 2);
  }

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
    long jobsBefore = count("SELECT count(*) FROM job");

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
    // everything moved with its pages, and nothing was queued
    assertThat(
            count(
                "SELECT count(*) FROM page_text WHERE page_id IN (?, ?)",
                pages.get(3),
                pages.get(4)))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM text_chunk WHERE record_id = ?", created)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore);
    assertThat(pagesMovedEvents(a)).isEqualTo(1);
    assertThat(pagesMovedEvents(created)).isEqualTo(1);
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

    assertThat(post("/admin/records/" + a + "/split", splitBody(2, "x")).statusCode())
        .isEqualTo(409);

    assertThat(count("SELECT count(*) FROM record")).isEqualTo(recordsBefore);
    assertThat(seqs(a)).containsExactly(1, 2, 3);
  }

  @Test
  void splittingAnUnknownRecordIsNotFound() throws Exception {
    assertThat(post("/admin/records/99999999/split", splitBody(2, "x")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void aSplitPointBeyondIntRangeIsRefusedAndCreatesNothing() throws Exception {
    long a = recordWithPages("a", 3);
    long recordsBefore = count("SELECT count(*) FROM record");
    assertThat(
            post("/admin/records/" + a + "/split", splitBody(2, "x").replace("2", "4294967298"))
                .statusCode())
        .isEqualTo(400);
    assertThat(count("SELECT count(*) FROM record")).isEqualTo(recordsBefore);
  }

  @Test
  void splittingAgainAtTheSameSeqAfterAPageMovedBackGetsADistinctSourceId() throws Exception {
    long a = recordWithPages("a", 3);
    long first =
        ok(post("/admin/records/" + a + "/split", splitBody(3, "one"))).get("newRecordId").asLong();
    long page = pageIds(first).get(0);
    ok(post(movePath(first, page), "{\"targetRecordId\":" + a + "}"));
    assertThat(seqs(a)).containsExactly(1, 2, 3);
    long second =
        ok(post("/admin/records/" + a + "/split", splitBody(3, "two"))).get("newRecordId").asLong();
    assertThat(second).isNotEqualTo(first);
    assertThat(
            count(
                "SELECT count(DISTINCT source_record_id) FROM record WHERE id IN (?, ?)",
                first,
                second))
        .isEqualTo(2);
  }

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
    long jobsBefore = count("SELECT count(*) FROM job");
    List<Long> attachmentsA = new java.util.ArrayList<>(attachmentIds(a));
    attachmentsA.addAll(attachmentIds(b));
    List<Long> chunksBefore =
        jdbc.sql("SELECT id FROM text_chunk WHERE record_id IN (:a, :b) ORDER BY id")
            .param("a", a)
            .param("b", b)
            .query(Long.class)
            .list();

    JsonNode out = ok(post(concatPath(a), "{\"sourceRecordId\":" + b + "}"));

    assertThat(out.get("targetPageCount").asInt()).isEqualTo(5);
    assertThat(out.get("sourcePageCount").asInt()).isZero();
    assertThat(pageIds(a))
        .containsExactly(aPages.get(0), aPages.get(1), aPages.get(2), bPages.get(0), bPages.get(1));
    assertThat(seqs(a)).containsExactly(1, 2, 3, 4, 5);
    assertThat(count("SELECT count(*) FROM record WHERE id = ?", b)).isEqualTo(1);
    assertThat(seqs(b)).isEmpty();
    assertThat(one("SELECT page_count FROM record WHERE id = ?", b)).isZero();
    assertThat(one("SELECT page_count FROM record WHERE id = ?", a)).isEqualTo(5);
    assertThat(one("SELECT attachment_count FROM record WHERE id = ?", a)).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM text_chunk WHERE record_id = ?", a)).isEqualTo(5);
    assertThat(
            count(
                "SELECT count(*) FROM page_text WHERE page_id IN (?, ?)",
                bPages.get(0),
                bPages.get(1)))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM job")).isEqualTo(jobsBefore);
    assertThat(attachmentIds(a)).containsExactlyInAnyOrderElementsOf(attachmentsA);
    assertThat(attachmentIds(b)).isEmpty();
    assertThat(
            count(
                "SELECT count(*) FROM job WHERE page_id IN (?, ?) AND record_id = ?",
                bPages.get(0),
                bPages.get(1),
                a))
        .isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT id FROM text_chunk WHERE record_id = :r ORDER BY id")
                .param("r", a)
                .query(Long.class)
                .list())
        .containsExactlyElementsOf(chunksBefore);
    assertThat(pagesMovedEvents(a)).isEqualTo(1);
    assertThat(pagesMovedEvents(b)).isEqualTo(1);
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
    assertThat(post(concatPath(a), "{\"sourceRecordId\":" + empty + "}").statusCode())
        .isEqualTo(400);
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

  private long pagesMovedEvents(long recordId) {
    return count(
        "SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'",
        recordId);
  }

  /** Leaves the record with page numbers 1, 2, 4, as an old delete once could. */
  private void makeGap(long recordId) {
    jdbc.sql("UPDATE page SET seq = 4 WHERE record_id = :r AND seq = 3")
        .param("r", recordId)
        .update();
  }

  // --- tests: contiguity ---------------------------------------------------------------------

  @Test
  void aRecordWithGapsInItsPageNumbersIsRefusedAsSourceOrTargetOfMoveSplitAndConcat()
      throws Exception {
    long gap = recordWithPages("gap", 3);
    makeGap(gap);
    long ok = recordWithPages("ok", 2);
    long gapPage = pageIds(gap).get(0);
    long okPage = pageIds(ok).get(0);
    long recordsBefore = count("SELECT count(*) FROM record");

    for (var resp :
        List.of(
            post(movePath(gap, gapPage), "{\"targetRecordId\":" + ok + "}"),
            post(movePath(ok, okPage), "{\"targetRecordId\":" + gap + "}"),
            post("/admin/records/" + gap + "/split", splitBody(2, "x")),
            post(concatPath(gap), "{\"sourceRecordId\":" + ok + "}"),
            post(concatPath(ok), "{\"sourceRecordId\":" + gap + "}"))) {
      assertThat(resp.statusCode()).as(resp.body()).isEqualTo(409);
      assertThat(resp.body()).contains("non-contiguous").contains("renumber first");
    }
    assertThat(seqs(gap)).containsExactly(1, 2, 4);
    assertThat(seqs(ok)).containsExactly(1, 2);
    assertThat(count("SELECT count(*) FROM record")).isEqualTo(recordsBefore);
    assertThat(count("SELECT count(*) FROM pipeline_event WHERE event = 'pages_moved'")).isZero();
  }

  // --- tests: split and the audit -------------------------------------------------------------

  @Autowired private PipelineAuditService audit;

  private long jobsOf(long recordId) {
    return count("SELECT count(*) FROM job WHERE record_id = ?", recordId);
  }

  @Test
  void theAuditLeavesASplitRecordAloneBecauseItsChunksCameWithItsPages() throws Exception {
    long a = recordWithPages("a", 4);
    long created =
        ok(post("/admin/records/" + a + "/split", splitBody(3, "tail")))
            .get("newRecordId")
            .asLong();
    List<Long> chunks =
        jdbc.sql("SELECT id FROM text_chunk WHERE record_id = :r ORDER BY id")
            .param("r", created)
            .query(Long.class)
            .list();
    long jobs = jobsOf(created);
    assertThat(chunks).hasSize(2);

    audit.auditPipeline();

    assertThat(statusOf(created)).isEqualTo("complete");
    assertThat(jobsOf(created)).isEqualTo(jobs);
    assertThat(
            count(
                "SELECT count(*) FROM job WHERE record_id = ? AND kind = 'embed_record'", created))
        .isZero();
    assertThat(
            jdbc.sql("SELECT id FROM text_chunk WHERE record_id = :r ORDER BY id")
                .param("r", created)
                .query(Long.class)
                .list())
        .containsExactlyElementsOf(chunks);
  }

  @Test
  void theAuditStillBackfillsACompleteRecordThatWasNeverEmbedded() throws Exception {
    long a = recordWithPages("a", 2);
    jdbc.sql("DELETE FROM text_chunk WHERE record_id = :r").param("r", a).update();

    audit.auditPipeline();

    assertThat(count("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'embed_record'", a))
        .isEqualTo(1);
    assertThat(statusOf(a)).isEqualTo("embedding");
  }

  // --- reordering within a record --------------------------------------------------------

  private String order(List<Long> ids) {
    return "{\"pageIds\":" + ids + "}";
  }

  @Test
  void aRecordsPagesCanBeReorderedAndTheirOutputFollowsThem() throws Exception {
    long a = recordWithPages("ord", 4);
    List<Long> before = pageIds(a);
    List<Long> wanted = List.of(before.get(3), before.get(2), before.get(1), before.get(0));
    long jobs = count("SELECT count(*) FROM job WHERE record_id = ?", a);
    long chunk0 = one("SELECT id FROM text_chunk WHERE page_id = ?", before.get(0));
    String text0 = text("SELECT text_raw FROM page_text WHERE page_id = ?", before.get(0));

    JsonNode out = ok(post("/admin/records/" + a + "/reorder", order(wanted)));

    assertThat(out.get("changed").asBoolean()).isTrue();
    assertThat(pageIds(a)).isEqualTo(wanted);
    assertThat(seqs(a)).containsExactly(1, 2, 3, 4);
    // the first page is now last, and everything keyed on it came with it
    assertThat(one("SELECT seq FROM page WHERE id = ?", before.get(0))).isEqualTo(4);
    assertThat(one("SELECT id FROM text_chunk WHERE page_id = ?", before.get(0))).isEqualTo(chunk0);
    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", before.get(0)))
        .isEqualTo(text0);
    assertThat(count("SELECT count(*) FROM job WHERE record_id = ?", a)).isEqualTo(jobs);
    assertThat(statusOf(a)).isEqualTo("complete");
    assertThat(
            count(
                "SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'",
                a))
        .isEqualTo(1);
  }

  @Test
  void onePageCanBeMovedToAPosition() throws Exception {
    long a = recordWithPages("pos", 3);
    List<Long> before = pageIds(a);
    ok(post("/admin/records/" + a + "/reorder", "{\"pageId\":" + before.get(2) + ",\"toSeq\":1}"));
    assertThat(pageIds(a)).containsExactly(before.get(2), before.get(0), before.get(1));
    assertThat(
            post(
                    "/admin/records/" + a + "/reorder",
                    "{\"pageId\":" + before.get(0) + ",\"toSeq\":9}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post("/admin/records/" + a + "/reorder", "{\"pageId\":99999999,\"toSeq\":1}")
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void anUnchangedOrderChangesNothingAndLogsNothing() throws Exception {
    long a = recordWithPages("same", 3);
    JsonNode out = ok(post("/admin/records/" + a + "/reorder", order(pageIds(a))));
    assertThat(out.get("changed").asBoolean()).isFalse();
    assertThat(
            count(
                "SELECT count(*) FROM pipeline_event WHERE record_id = ? AND event = 'pages_moved'",
                a))
        .isZero();
  }

  @Test
  void aListThatIsNotExactlyTheRecordsPagesIsRefusedAndNothingChanges() throws Exception {
    long a = recordWithPages("bad", 3);
    long b = recordWithPages("other", 1);
    List<Long> mine = pageIds(a);
    String p = "/admin/records/" + a + "/reorder";
    assertThat(post(p, order(mine.subList(0, 2))).statusCode()).isEqualTo(400);
    assertThat(post(p, order(List.of(mine.get(0), mine.get(0), mine.get(1)))).statusCode())
        .isEqualTo(400);
    assertThat(post(p, order(List.of(mine.get(0), mine.get(1), pageIds(b).get(0)))).statusCode())
        .isEqualTo(400);
    assertThat(post(p, "{\"pageIds\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(post(p, "{\"pageIds\":[1.5,2,3]}").statusCode()).isEqualTo(400);
    assertThat(post(p, "{}").statusCode()).isEqualTo(400);
    assertThat(post("/admin/records/99999999/reorder", order(mine)).statusCode()).isEqualTo(404);
    assertThat(pageIds(a)).isEqualTo(mine);
  }

  @Test
  void aRecordThatIsHeldBusyOrNotCompleteIsNotReordered() throws Exception {
    long a = recordWithPages("busy", 2);
    List<Long> mine = pageIds(a);
    List<Long> swapped = List.of(mine.get(1), mine.get(0));
    String p = "/admin/records/" + a + "/reorder";

    jdbc.sql("UPDATE record SET ai_held_at = now() WHERE id = :r").param("r", a).update();
    assertThat(post(p, order(swapped)).statusCode()).isEqualTo(409);
    jdbc.sql("UPDATE record SET ai_held_at = NULL WHERE id = :r").param("r", a).update();

    jdbc.sql(
            "INSERT INTO job (kind, record_id, page_id, status) VALUES ('translate_page', :r, :p, 'pending')")
        .param("r", a)
        .param("p", mine.get(0))
        .update();
    assertThat(post(p, order(swapped)).statusCode()).isEqualTo(409);
    jdbc.sql("DELETE FROM job WHERE record_id = :r AND status = 'pending'").param("r", a).update();

    jdbc.sql("UPDATE record SET status = 'translating' WHERE id = :r").param("r", a).update();
    assertThat(post(p, order(swapped)).statusCode()).isEqualTo(409);
    assertThat(pageIds(a)).isEqualTo(mine);
  }

  @Test
  void aJobQueuedForAPageLandsOnTheRecordThePageIsInNow() throws Exception {
    long a = recordWithPages("race-a", 2);
    long b = recordWithPages("race-b", 1);
    long moved = pageIds(a).get(1);
    ok(post("/admin/records/" + a + "/pages/" + moved + "/move", "{\"targetRecordId\":" + b + "}"));

    // an enqueuer that read the page's record before the move still names the old one
    jobService.enqueueJob("translate_page", a, moved, null);

    assertThat(one("SELECT record_id FROM job WHERE page_id = ? AND status = 'pending'", moved))
        .isEqualTo(b);
  }
}
