package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the read-only endpoints that are built from hand-written SQL.
 *
 * <p>These carry no compile-time protection: a table rename, a dropped column or a typo in a string
 * literal is invisible until someone loads the page. That happened — V4 renamed ocr_batch to
 * provider_batch, the repositories were updated, and a raw query in ViewerController was not, so
 * /pipeline returned 500 the moment the migration applied while the whole suite stayed green.
 *
 * <p>Asserting only that each endpoint answers 200 against a real schema is enough to catch that
 * entire class of fault, and costs nothing to maintain.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatsEndpointsTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  /** Admin endpoints need ROLE_ADMIN, which the static admin token grants. */
  private static final String ADMIN_TOKEN = "test-admin-token";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.admin.token", () -> ADMIN_TOKEN);
  }

  private HttpResponse<String> get(String path) throws Exception {
    // Admin paths require ROLE_ADMIN; everything else is readable with the processor token.
    String auth = path.startsWith("/api/admin/") ? "Bearer " + ADMIN_TOKEN : PROCESSOR_AUTH_HEADER;
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Authorization", auth)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/pipeline/stats",
        "/api/admin/stats",
        "/api/admin/gates",
        "/api/viewer/source-status",
        "/api/translate/capabilities"
      })
  void statsEndpointsAnswerAgainstARealSchema(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("%s should not fail on its SQL", path).isEqualTo(200);
  }

  @Test
  @SuppressWarnings("unchecked")
  void pipelineStatsCarriesTheFieldsTheDashboardReads() throws Exception {
    HttpResponse<String> response = get("/api/pipeline/stats");
    assertThat(response.statusCode()).isEqualTo(200);

    Map<String, Object> body = mapper.readValue(response.body(), Map.class);
    assertThat(body).containsKeys("stages", "pausedKinds");

    var stages = (java.util.List<Map<String, Object>>) body.get("stages");
    assertThat(stages).isNotEmpty();

    var ocr =
        stages.stream()
            .filter(s -> "OCR".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no OCR stage in pipeline stats"));

    // The batch block is built from raw SQL against provider_batch — the query that broke.
    assertThat(ocr).containsKey("batches");
    var batches = (Map<String, Object>) ocr.get("batches");
    assertThat(batches)
        .containsKeys("in_flight", "pages_in_flight", "pages_billed_total", "cost_total");
  }
}
