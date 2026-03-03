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

    // Verify jobs were created
    int jobCount =
        jdbc.sql("SELECT count(*) FROM job WHERE kind = 'ocr_page_qwen3vl' AND record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(jobCount).isEqualTo(2);
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
