package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
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
 * The upgrade path must never pay for the same page twice.
 *
 * <p>A stronger translation model costs about ten times the bulk one, so "already done" has to be
 * answerable rather than assumed — which is why every model's output is kept in page_translation
 * instead of overwriting a single column.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TranslationUpgradeTest {

  private static final String ADMIN_TOKEN = "test-admin-token";

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

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.admin.token", () -> ADMIN_TOKEN);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String path) throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as("POST %s -> %s", path, r.body()).isEqualTo(200);
    return mapper.readValue(r.body(), Map.class);
  }

  private long seedRecordWithPages(int pages) {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES ('T','CZ') RETURNING id")
            .query(Long.class)
            .single();
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status,
                                    page_count, attachment_count, created_at, updated_at)
                VALUES (:a, 'test', :s, 'complete', :n, 1, now(), now()) RETURNING id
                """)
            .param("a", archiveId)
            .param("s", "UP-" + System.nanoTime())
            .param("n", pages)
            .query(Long.class)
            .single();
    Long attId =
        jdbc.sql(
                """
                INSERT INTO attachment (record_id, role, path, created_at)
                VALUES (:r, 'page_image', 'x.jpg', now()) RETURNING id
                """)
            .param("r", recordId)
            .query(Long.class)
            .single();
    for (int i = 1; i <= pages; i++) {
      Long pageId =
          jdbc.sql(
                  "INSERT INTO page (record_id, seq, attachment_id) VALUES (:r,:s,:a) RETURNING id")
              .param("r", recordId)
              .param("s", i)
              .param("a", attId)
              .query(Long.class)
              .single();
      jdbc.sql(
              """
              INSERT INTO page_text (page_id, engine, text_raw, created_at)
              VALUES (:p, 'mistral-ocr', 'Guten Tag', now())
              """)
          .param("p", pageId)
          .update();
    }
    return recordId;
  }

  private void markUpgraded(long recordId) {
    jdbc.sql(
            """
            INSERT INTO page_translation (page_id, model, text_en, created_at)
            SELECT p.id, 'mistral-medium-latest', 'Good day', now()
            FROM page p WHERE p.record_id = :r
            """)
        .param("r", recordId)
        .update();
  }

  private int queuedUpgradeJobs(long recordId) {
    return jdbc.sql(
            "SELECT count(*) FROM job WHERE record_id = :r AND kind = 'translate_page_upgrade'")
        .param("r", recordId)
        .query(Integer.class)
        .single();
  }

  @Test
  void queuesAnUpgradeForPagesThatLackIt() throws Exception {
    long recordId = seedRecordWithPages(3);

    Map<String, Object> body = post("/api/records/" + recordId + "/translate-upgrade");

    assertThat(body.get("queued")).isEqualTo(3);
    assertThat(queuedUpgradeJobs(recordId)).isEqualTo(3);
  }

  @Test
  void refusesWhenEveryPageIsAlreadyUpgraded() throws Exception {
    long recordId = seedRecordWithPages(3);
    markUpgraded(recordId);

    Map<String, Object> body = post("/api/records/" + recordId + "/translate-upgrade");

    assertThat(body.get("queued")).isEqualTo(0);
    assertThat(body.get("alreadyUpgraded")).isEqualTo(3);
    assertThat(queuedUpgradeJobs(recordId)).isZero();
  }

  @Test
  void aSecondClickWhileRunningQueuesNothingMore() throws Exception {
    long recordId = seedRecordWithPages(3);

    post("/api/records/" + recordId + "/translate-upgrade");
    Map<String, Object> second = post("/api/records/" + recordId + "/translate-upgrade");

    assertThat(second.get("queued")).isEqualTo(0);
    assertThat(second.get("inFlight")).isEqualTo(3);
    // Three jobs, not six: an impatient click must not double the bill.
    assertThat(queuedUpgradeJobs(recordId)).isEqualTo(3);
  }

  @Test
  void statusReportsWhetherAnUpgradeIsAvailable() throws Exception {
    long recordId = seedRecordWithPages(2);

    HttpResponse<String> before =
        http.send(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/records/"
                            + recordId
                            + "/translation-status"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    @SuppressWarnings("unchecked")
    Map<String, Object> b = mapper.readValue(before.body(), Map.class);
    assertThat(b.get("canUpgrade")).isEqualTo(true);
    assertThat(b.get("pagesUpgradable")).isEqualTo(2);

    markUpgraded(recordId);

    HttpResponse<String> after =
        http.send(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/records/"
                            + recordId
                            + "/translation-status"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    @SuppressWarnings("unchecked")
    Map<String, Object> a = mapper.readValue(after.body(), Map.class);
    assertThat(a.get("canUpgrade")).isEqualTo(false);
    assertThat(a.get("pagesUpgraded")).isEqualTo(2);
  }
}
