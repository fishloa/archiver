package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
}
