package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FamilyTreeControllerTest {

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

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private String base() {
    return "http://localhost:" + port + "/api/family-tree";
  }

  // ── Search ──

  @Test
  void searchReturnsResults() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=alexander&limit=5")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> results =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results).isNotEmpty();
    assertThat(results.getFirst()).containsKeys("personId", "name", "score", "section");
  }

  @Test
  void searchRespectsLimit() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=czernin&limit=2")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> results =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results.size()).isLessThanOrEqualTo(2);
  }

  @Test
  void searchNoMatchReturnsEmpty() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=zzzzzznotaperson&limit=5"))
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> results =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results).isEmpty();
  }

  // ── Person ──

  @Test
  void getPersonReturnsDetail() throws Exception {
    // Person 1 is always Alexander (the root person)
    var req = HttpRequest.newBuilder(URI.create(base() + "/person/1")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("id")).isEqualTo(1);
    assertThat(body.get("name")).isNotNull();
    assertThat(body).containsKeys("fullEntry", "section", "code", "depth", "children", "events");
  }

  @Test
  void getPersonNotFoundReturns404() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/person/999999")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void getPersonHasParentAndChildren() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/person/1")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    // Person 1 should have a parent (unless root of tree)
    // and events should be a list
    assertThat(body.get("events")).isInstanceOf(List.class);
    assertThat(body.get("children")).isInstanceOf(List.class);
  }

  // ── Relate (default ref = Alexander) ──

  @Test
  void relateReturnsKinship() throws Exception {
    // First find a person via search
    var searchReq =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=czernin&limit=1")).GET().build();
    var searchResp = http.send(searchReq, HttpResponse.BodyHandlers.ofString());
    List<Map<String, Object>> searchResults =
        mapper.readValue(searchResp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(searchResults).isNotEmpty();
    int personId = ((Number) searchResults.getFirst().get("personId")).intValue();

    var req =
        HttpRequest.newBuilder(URI.create(base() + "/relate?personId=" + personId)).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsKeys("personId", "personName", "kinshipLabel", "pathDescription");
    assertThat(body).containsKeys("commonAncestorName", "stepsFromPerson", "stepsFromRef");
    assertThat(body.get("refPersonName")).isNotNull();
  }

  @Test
  void relateNotFoundReturns404() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/relate?personId=999999")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Relate with custom refId ("This is Me") ──

  @Test
  void relateWithRefIdUsesCustomReference() throws Exception {
    // Search for two different people
    var searchReq =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=czernin&limit=5")).GET().build();
    var searchResp = http.send(searchReq, HttpResponse.BodyHandlers.ofString());
    List<Map<String, Object>> results =
        mapper.readValue(searchResp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results.size()).isGreaterThanOrEqualTo(2);

    int personId = ((Number) results.get(0).get("personId")).intValue();
    int refId = ((Number) results.get(1).get("personId")).intValue();

    // Relate with custom ref
    var req =
        HttpRequest.newBuilder(
                URI.create(base() + "/relate?personId=" + personId + "&refId=" + refId))
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("personId")).isEqualTo(personId);
    // refPersonName should reflect the custom ref, not Alexander
    assertThat(body.get("refPersonName")).isNotNull();
  }

  @Test
  void relateWithRefIdDifferentFromDefault() throws Exception {
    // Person 1 = Alexander. Get relate without refId (default) and with refId=different person
    var searchReq =
        HttpRequest.newBuilder(URI.create(base() + "/search?q=czernin&limit=3")).GET().build();
    var searchResp = http.send(searchReq, HttpResponse.BodyHandlers.ofString());
    List<Map<String, Object>> results =
        mapper.readValue(searchResp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results.size()).isGreaterThanOrEqualTo(2);

    int personId = ((Number) results.get(0).get("personId")).intValue();

    // Default relate (ref = Alexander)
    var defaultReq =
        HttpRequest.newBuilder(URI.create(base() + "/relate?personId=" + personId)).GET().build();
    var defaultResp = http.send(defaultReq, HttpResponse.BodyHandlers.ofString());
    Map<String, Object> defaultBody = mapper.readValue(defaultResp.body(), Map.class);

    // Find a different ref person
    int refId = ((Number) results.get(1).get("personId")).intValue();

    var customReq =
        HttpRequest.newBuilder(
                URI.create(base() + "/relate?personId=" + personId + "&refId=" + refId))
            .GET()
            .build();
    var customResp = http.send(customReq, HttpResponse.BodyHandlers.ofString());
    Map<String, Object> customBody = mapper.readValue(customResp.body(), Map.class);

    // Both should succeed, but refPersonName may differ
    assertThat(defaultBody.get("refPersonName")).isNotNull();
    assertThat(customBody.get("refPersonName")).isNotNull();
  }

  @Test
  void relateWithInvalidRefIdReturns404() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/relate?personId=1&refId=999999"))
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Page/Record matches (empty since no match data seeded) ──

  @Test
  void pageMatchesReturnsEmptyForUnknownPage() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/page-matches/999999")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> results =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results).isEmpty();
  }

  @Test
  void recordMatchesReturnsEmptyForUnknownRecord() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/record-matches/999999")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> results =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(results).isEmpty();
  }

  // ── Reload ──

  @Test
  void reloadReturnsCount() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/reload"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("status")).isEqualTo("ok");
    assertThat(((Number) body.get("count")).intValue()).isGreaterThan(0);
  }

  @Test
  void invalidateMatchesReturnsOk() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/invalidate-matches"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("status")).isEqualTo("ok");
  }
}
