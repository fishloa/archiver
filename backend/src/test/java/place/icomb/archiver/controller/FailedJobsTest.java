package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Failed jobs that will never be retried on their own: retry the ones that can run, dismiss the
 * rest.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FailedJobsTest {

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
  private final ObjectMapper json = new ObjectMapper();
  private static final String ADMIN_EMAIL = "failed-jobs-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM job").update();
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'FailedJobsAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('FailedJobsAdmin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  private long failed(String kind, String error, int attempts) {
    return jdbc.sql(
            "INSERT INTO job (kind, status, attempts, error, finished_at)"
                + " VALUES (:k, 'failed', :a, :e, now()) RETURNING id")
        .param("k", kind)
        .param("a", attempts)
        .param("e", error)
        .query(Long.class)
        .single();
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/admin" + path))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String statusOf(long id) {
    return jdbc.sql("SELECT status FROM job WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .single();
  }

  @Test
  void aFailedJobThatCanStillRunStartsAgainFromTheBeginning() throws Exception {
    long timedOut = failed("translate_page", "The read operation timed out", 3);
    long cancelled = failed("translate_page", "cancelled by admin reset", 1);
    long other = failed("embed_record", "boom", 3);

    HttpResponse<String> resp = post("/retry-failed-jobs?kind=translate_page");
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    assertThat(json.readTree(resp.body()).get("retried").asInt()).isEqualTo(1);

    assertThat(statusOf(timedOut)).isEqualTo("pending");
    assertThat(
            jdbc.sql("SELECT attempts FROM job WHERE id = :i")
                .param("i", timedOut)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT error FROM job WHERE id = :i")
                .param("i", timedOut)
                .query(String.class)
                .optional())
        .isEmpty();
    // a cancelled job stays cancelled, and another kind is not touched
    assertThat(statusOf(cancelled)).isEqualTo("failed");
    assertThat(statusOf(other)).isEqualTo("failed");
  }

  @Test
  void aKindThatCanNoLongerRunIsNotRetriedAndAKindThatCanIsNotDismissed() throws Exception {
    failed("ocr_page_paddle", "gone", 3);
    failed("translate_page", "timeout", 3);
    assertThat(post("/retry-failed-jobs?kind=ocr_page_paddle").statusCode()).isEqualTo(400);
    assertThat(post("/dismiss-failed-jobs?kind=translate_page&reason=x").statusCode())
        .isEqualTo(400);
    assertThat(post("/retry-failed-jobs?kind=translate_page&limit=0").statusCode()).isEqualTo(400);
  }

  @Test
  void retiredFailuresAreDismissedWithAReasonAndKept() throws Exception {
    long a = failed("ocr_page_paddle", "gone", 3);
    long b = failed("ocr_page_paddle", "gone too", 3);
    HttpResponse<String> resp =
        post("/dismiss-failed-jobs?kind=ocr_page_paddle&reason=engine+retired");
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    assertThat(json.readTree(resp.body()).get("dismissed").asInt()).isEqualTo(2);
    assertThat(statusOf(a)).isEqualTo("completed");
    assertThat(statusOf(b)).isEqualTo("completed");
    assertThat(
            jdbc.sql("SELECT error FROM job WHERE id = :i")
                .param("i", a)
                .query(String.class)
                .single())
        .contains("engine retired");
    assertThat(post("/dismiss-failed-jobs?kind=ocr_page_paddle&reason=%20").statusCode())
        .isEqualTo(400);
  }
}
