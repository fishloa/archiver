package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPipelineControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private place.icomb.archiver.service.JobService jobService;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private static final String ADMIN_EMAIL = "pipeline-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void registerAnOcrEngine() {
    // The endpoint refuses to clear a record's text unless something can transcribe it again.
    // A local engine needs no API key, so this holds on a developer's machine and on CI alike.
    jdbc.sql("UPDATE ai_implementation SET enabled = false WHERE capability = 'OCR'").update();
    jdbc.sql(
            """
            INSERT INTO ai_implementation
                (id, capability, provider, model, base_url, endpoint_path, credential_env,
                 max_batch_size, rank, enabled, settings)
            VALUES ('local:test-ocr', 'OCR', 'local', 'test-ocr', 'http://localhost:1/v1',
                    '/v1/ocr', NULL, 1, 0, true, '{"jobKind": "ocr_page_mistral"}')
            ON CONFLICT (id) DO UPDATE SET enabled = true
            """)
        .update();
  }

  @BeforeEach
  void setUp() {
    // Clean up test data (order matters: FK constraints)
    jdbc.sql("DELETE FROM job WHERE kind = 'ocr_page_qwen3vl'").update();
    jdbc.sql(
            "DELETE FROM page WHERE record_id IN (SELECT id FROM record WHERE title LIKE 'ReOCR%')")
        .update();
    jdbc.sql(
            "DELETE FROM attachment WHERE record_id IN (SELECT id FROM record WHERE title LIKE 'ReOCR%')")
        .update();
    jdbc.sql("DELETE FROM record WHERE title LIKE 'ReOCR%'").update();
    jdbc.sql("DELETE FROM archive WHERE name = 'ReOCR TestArchive'").update();
    jdbc.sql("DELETE FROM app_user_email WHERE email = :email")
        .param("email", ADMIN_EMAIL)
        .update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'PipelineAdmin'").update();

    // Create admin user for auth
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('PipelineAdmin', 'admin') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :email)")
        .param("uid", adminId)
        .param("email", ADMIN_EMAIL)
        .update();
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void enqueueReocrCreatesJobsForOcrDonePages() throws Exception {
    // Insert archive, record (ocr_done), attachment, and two pages
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name) VALUES ('ReOCR TestArchive') RETURNING id")
            .query(Long.class)
            .single();

    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, title, status, lang)
                VALUES (:aid, 'test', 'reocr-1', 'ReOCR Test Record', 'ocr_done', 'de')
                RETURNING id
                """)
            .param("aid", archiveId)
            .query(Long.class)
            .single();

    Long attId =
        jdbc.sql(
                """
                INSERT INTO attachment (record_id, role, path)
                VALUES (:rid, 'scan', 'test/dummy.jpg')
                RETURNING id
                """)
            .param("rid", recordId)
            .query(Long.class)
            .single();

    jdbc.sql("INSERT INTO page (record_id, seq, attachment_id) VALUES (:rid, 1, :att)")
        .param("rid", recordId)
        .param("att", attId)
        .update();
    jdbc.sql("INSERT INTO page (record_id, seq, attachment_id) VALUES (:rid, 2, :att)")
        .param("rid", recordId)
        .param("att", attId)
        .update();

    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/enqueue-reocr")))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(((Number) body.get("jobsEnqueued")).intValue()).isGreaterThanOrEqualTo(2);

    // The jobs are for the engine that is actually enabled, not a hardcoded one. This asserted
    // ocr_page_qwen3vl, which has been disabled in the deployment since Mistral became the only
    // engine — so the endpoint cleared each record's text and queued work nothing could claim.
    assertThat(body.get("engine")).isEqualTo("ocr_page_mistral");
    int jobCount =
        jdbc.sql("SELECT count(*) FROM job WHERE kind = :kind AND record_id = :rid")
            .param("kind", "ocr_page_mistral")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(jobCount).isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT count(*) FROM job WHERE kind = 'ocr_page_qwen3vl'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void enqueueReocrDestroysNothingWhenNoEngineIsConfigured() throws Exception {
    // It deletes each record's text, searchable PDF and translations before queuing. With no
    // engine to transcribe them again, the deletion happens and the repair never does.
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name) VALUES ('No Engine') RETURNING id")
            .query(Long.class)
            .single();
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                    lang)
                VALUES (:aid, 'test', 'reocr-none', 'Keep My Text', 'complete', 'de')
                RETURNING id
                """)
            .param("aid", archiveId)
            .query(Long.class)
            .single();
    Long attId =
        jdbc.sql(
                """
                INSERT INTO attachment (record_id, role, path, mime, bytes, created_at)
                VALUES (:rid, 'page_image', 'x.jpg', 'image/jpeg', 1, now()) RETURNING id
                """)
            .param("rid", recordId)
            .query(Long.class)
            .single();
    Long pageId =
        jdbc.sql(
                "INSERT INTO page (record_id, seq, attachment_id) VALUES (:rid, 1, :att)"
                    + " RETURNING id")
            .param("rid", recordId)
            .param("att", attId)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO page_text (page_id, engine, text_raw, content_type, created_at)
            VALUES (:pid, 'ocr_page_mistral', 'irreplaceable transcription', 'text/plain', now())
            """)
        .param("pid", pageId)
        .update();

    jdbc.sql("UPDATE ai_implementation SET enabled = false WHERE capability = 'OCR'").update();

    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/admin/enqueue-reocr")))
                .header("X-Auth-Email", ADMIN_EMAIL)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(409);
    assertThat(
            jdbc.sql("SELECT text_raw FROM page_text WHERE page_id = :pid")
                .param("pid", pageId)
                .query(String.class)
                .single())
        .isEqualTo("irreplaceable transcription");
  }

  @Test
  void enqueueReocrWithoutAuthReturns403() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/enqueue-reocr")))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(403);
  }

  @Test
  void enqueueReocrReturnsZeroWhenNoPagesExist() throws Exception {
    // No records in qualifying states — should return 0
    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/enqueue-reocr")))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    // May be 0 or more depending on pre-existing data, but should not error
    assertThat(body).containsKey("jobsEnqueued");
  }

  // --- cancel-jobs -------------------------------------------------------------------------

  /** A record with three jobs on it: two still queued, one already finished. */
  private long seedJobs() {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name) VALUES ('ReOCR TestArchive') RETURNING id")
            .query(Long.class)
            .single();
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                    lang, metadata_lang)
                VALUES (?, 'test', ?, 'ReOCR cancel', 'ocr_pending', 'de', 'de')
                RETURNING id
                """)
            .params(archiveId, "cancel-" + System.nanoTime())
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO job (kind, record_id, status) VALUES ('translate_page', ?, 'pending')")
        .params(recordId)
        .update();
    jdbc.sql("INSERT INTO job (kind, record_id, status) VALUES ('embed_record', ?, 'claimed')")
        .params(recordId)
        .update();
    jdbc.sql("INSERT INTO job (kind, record_id, status) VALUES ('embed_record', ?, 'completed')")
        .params(recordId)
        .update();
    return recordId;
  }

  private HttpResponse<String> cancel(String query) throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/cancel-jobs" + query)))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private long countStatus(long recordId, String status) {
    return jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND status = ?")
        .params(recordId, status)
        .query(Long.class)
        .single();
  }

  private HttpResponse<String> hold(long recordId, String query) throws Exception {
    var req =
        HttpRequest.newBuilder(
                URI.create(url("/api/admin/records/" + recordId + "/ai-hold" + query)))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aHeldRecordKeepsItsQueuedWork() throws Exception {
    long recordId = seedJobs();

    var resp = hold(recordId, "?reason=bad+re-OCR");

    assertThat(resp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsEntry("held", true).containsEntry("cancelled", 0);
    // Held like a paused stage, not a cancelled one: the work waits for the hold to lift.
    assertThat(countStatus(recordId, "pending")).isEqualTo(1);
    assertThat(countStatus(recordId, "claimed")).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT ai_hold_reason FROM record WHERE id = ?")
                .params(recordId)
                .query(String.class)
                .single())
        .isEqualTo("bad re-OCR");
  }

  @Test
  void aHoldCanThrowAwayTheQueuedWorkWithIt() throws Exception {
    long recordId = seedJobs();

    var resp = hold(recordId, "?cancelQueued=true");

    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsEntry("cancelled", 2);
    // The finished job is left exactly as it was: cancelling is not rewriting history.
    assertThat(countStatus(recordId, "completed")).isEqualTo(1);
    assertThat(countStatus(recordId, "failed")).isEqualTo(2);
  }

  @Test
  void liftingTheHoldClearsTheReason() throws Exception {
    long recordId = seedJobs();
    hold(recordId, "?reason=temporary");

    hold(recordId, "?hold=false");

    assertThat(
            jdbc.sql("SELECT count(*) FROM record WHERE id = ? AND ai_held_at IS NULL")
                .params(recordId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void holdingARecordThatDoesNotExistIs404() throws Exception {
    assertThat(hold(-1L, "").statusCode()).isEqualTo(404);
  }

  @Test
  void aHeldRecordsJobsAreNotClaimed() {
    long recordId = seedJobs();
    jdbc.sql("UPDATE record SET ai_held_at = now() WHERE id = ?").params(recordId).update();

    // The claim query is the gate: a worker asking for translate_page work sees nothing of this
    // record, so a broken document cannot spend money while it is being put right.
    var claimed = jobService.claimJob("translate_page");

    assertThat(claimed).isEmpty();
  }

  @Test
  void liftingTheHoldLetsTheWorkThrough() {
    long recordId = seedJobs();
    jdbc.sql("UPDATE record SET ai_held_at = now() WHERE id = ?").params(recordId).update();
    assertThat(jobService.claimJob("translate_page")).isEmpty();

    jdbc.sql("UPDATE record SET ai_held_at = NULL WHERE id = ?").params(recordId).update();

    assertThat(jobService.claimJob("translate_page"))
        .get()
        .extracting(place.icomb.archiver.model.Job::getRecordId)
        .isEqualTo(recordId);
  }

  @Test
  void cancellingOneJobLeavesTheRecordsOtherWorkAlone() throws Exception {
    long recordId = seedJobs();
    Long jobId =
        jdbc.sql("SELECT id FROM job WHERE record_id = ? AND kind = 'translate_page'")
            .params(recordId)
            .query(Long.class)
            .single();

    var resp = cancel("?jobId=" + jobId);

    assertThat(resp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsEntry("cancelled", 1);
    assertThat(countStatus(recordId, "claimed")).isEqualTo(1);
  }

  @Test
  void aCancelMustNameTheJob() throws Exception {
    // A record's queued work is stopped through its AI hold, which also stops more being queued.
    assertThat(cancel("").statusCode()).isEqualTo(400);
  }

  @Test
  void cancellingSomethingAlreadyFinishedChangesNothing() throws Exception {
    long recordId = seedJobs();
    Long done =
        jdbc.sql("SELECT id FROM job WHERE record_id = ? AND status = 'completed'")
            .params(recordId)
            .query(Long.class)
            .single();

    var resp = cancel("?jobId=" + done);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsEntry("cancelled", 0);
    assertThat(countStatus(recordId, "completed")).isEqualTo(1);
  }

  @Test
  void jobsCanBeListedSoACancelHasAnIdToUse() throws Exception {
    long recordId = seedJobs();

    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/jobs?recordId=" + recordId)))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> jobs = mapper.readValue(resp.body(), List.class);
    assertThat(jobs).hasSize(3);
    assertThat(jobs.get(0)).containsKeys("id", "kind", "status", "recordId");
  }

  @Test
  void theJobListingNarrowsToOneStatus() throws Exception {
    long recordId = seedJobs();

    var req =
        HttpRequest.newBuilder(
                URI.create(url("/api/admin/jobs?recordId=" + recordId + "&status=pending")))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> jobs = mapper.readValue(resp.body(), List.class);
    assertThat(jobs).singleElement().extracting("kind").isEqualTo("translate_page");
  }

  // --- catalogue ---------------------------------------------------------------------------

  // Titles here keep the "ReOCR" prefix: setUp() cleans this suite's records by title, and a
  // record renamed out of that pattern survives and blocks the archive delete behind it.
  private HttpResponse<String> catalogue(long recordId, String query) throws Exception {
    var req =
        HttpRequest.newBuilder(
                URI.create(url("/api/admin/records/" + recordId + "/catalogue" + query)))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aTitleCanBeCorrected() throws Exception {
    long recordId = seedJobs();

    var resp = catalogue(recordId, "?title=ReOCR+Felix+Czernin+%E2%80%94+Akt+M.Nr.+3315");

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(
            jdbc.sql("SELECT title FROM record WHERE id = ?")
                .params(recordId)
                .query(String.class)
                .single())
        .isEqualTo("ReOCR Felix Czernin — Akt M.Nr. 3315");
  }

  @Test
  void whatIsNotGivenIsLeftAlone() throws Exception {
    long recordId = seedJobs();
    jdbc.sql("UPDATE record SET description = 'original description' WHERE id = ?")
        .params(recordId)
        .update();

    catalogue(recordId, "?title=ReOCR+new+title");

    assertThat(
            jdbc.sql("SELECT description FROM record WHERE id = ?")
                .params(recordId)
                .query(String.class)
                .single())
        .isEqualTo("original description");
  }

  @Test
  void theEnglishIsClearedSoItCannotDescribeTheOldText() throws Exception {
    long recordId = seedJobs();
    jdbc.sql("UPDATE record SET title_en = 'stale english title' WHERE id = ?")
        .params(recordId)
        .update();

    catalogue(recordId, "?title=ReOCR+corrected");

    assertThat(
            jdbc.sql("SELECT count(*) FROM record WHERE id = ? AND title_en IS NULL")
                .params(recordId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    // ...and re-translated, rather than left blank.
    assertThat(
            jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'translate_record'")
                .params(recordId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void aCatalogueCallMustChangeSomething() throws Exception {
    long recordId = seedJobs();

    assertThat(catalogue(recordId, "").statusCode()).isEqualTo(400);
  }

  @Test
  void transkribusUploadAnswers404ForAnUnknownRecord() throws Exception {
    // The hold check reads the record row; asking for one that is not there used to throw out of
    // the query and surface as a 500, which tells the caller nothing.
    var req =
        HttpRequest.newBuilder(URI.create(url("/api/admin/transkribus/upload?recordId=987654321")))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    // 409 when no Transkribus row is configured in this environment, 404 when one is: either way
    // it must not be a 500.
    assertThat(resp.statusCode()).isIn(404, 409);
    assertThat(resp.body()).doesNotContain("Internal server error");
  }
}
