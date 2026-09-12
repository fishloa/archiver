package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Managing which models do which job.
 *
 * <p>The preference order decides both where new work goes and which of several stored translations
 * is served, so getting it wrong is not cosmetic.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiAdminControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String ADMIN_TOKEN = "test-admin-token";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.admin.token", () -> ADMIN_TOKEN);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void resetOrder() {
    jdbc.update("DELETE FROM page_translation");
    // Anything a test registered. This used to delete by provider, which worked only while
    // 'custom' and 'local' were free-text values no seeded row used; provider names a protocol
    // now, so tests share those values and their rows survived to pollute the next test's order.
    jdbc.update(
        """
        DELETE FROM ai_implementation WHERE id NOT IN (
          'mistral:mistral-ocr-latest', 'mistral:mistral-medium-latest',
          'mistral:mistral-small-latest', 'deepinfra:google/gemma-4-31B-it',
          'deepinfra:Qwen/Qwen3-Embedding-8B', 'legacy')
        """);
    jdbc.update("UPDATE ai_implementation SET rank = rank + 100 WHERE capability = 'TRANSLATION'");
    jdbc.update("UPDATE ai_implementation SET rank = 1 WHERE id = 'mistral:mistral-medium-latest'");
    jdbc.update("UPDATE ai_implementation SET rank = 2 WHERE id = 'mistral:mistral-small-latest'");
    jdbc.update(
        "UPDATE ai_implementation SET rank = 3 WHERE id = 'deepinfra:google/gemma-4-31B-it'");
    jdbc.update("UPDATE ai_implementation SET rank = 99 WHERE id = 'legacy'");
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .header("Content-Type", "application/json");
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void listsImplementationsGroupedByCapabilityInPreferenceOrder() throws Exception {
    HttpResponse<String> res = send("GET", "/api/admin/ai/implementations", null);
    assertThat(res.statusCode()).isEqualTo(200);

    JsonNode body = mapper.readTree(res.body());
    JsonNode translation = body.get("TRANSLATION");
    assertThat(translation.get(0).get("id").asText()).isEqualTo("mistral:mistral-medium-latest");
    assertThat(translation.get(1).get("id").asText()).isEqualTo("mistral:mistral-small-latest");
    assertThat(body.has("OCR")).isTrue();
    assertThat(body.has("EMBEDDING")).isTrue();
  }

  @Test
  void neverReturnsACredential() throws Exception {
    // The row names an environment variable; the value stays in the deployment. An admin needs to
    // know whether a row is usable, not what the key is.
    JsonNode body = mapper.readTree(send("GET", "/api/admin/ai/implementations", null).body());
    JsonNode first = body.get("TRANSLATION").get(0);

    assertThat(first.has("credential_env")).isTrue();
    assertThat(first.has("credentialPresent")).isTrue();
    assertThat(res(body)).doesNotContain("sk-");
    assertThat(first.has("credential")).isFalse();
    assertThat(first.has("apiKey")).isFalse();
  }

  private String res(JsonNode body) {
    return body.toString();
  }

  @Test
  void reorderingChangesThePreference() throws Exception {
    String order =
        """
        {"order": ["mistral:mistral-small-latest", "mistral:mistral-medium-latest",
                   "deepinfra:google/gemma-4-31B-it", "legacy"]}
        """;
    HttpResponse<String> res = send("PUT", "/api/admin/ai/capabilities/TRANSLATION/order", order);
    assertThat(res.statusCode()).isEqualTo(200);

    JsonNode body = mapper.readTree(send("GET", "/api/admin/ai/implementations", null).body());
    assertThat(body.get("TRANSLATION").get(0).get("id").asText())
        .isEqualTo("mistral:mistral-small-latest");
  }

  @Test
  void aPartialOrderIsRefused() throws Exception {
    // Half an order would leave the rest at ranks that collide or contradict.
    HttpResponse<String> res =
        send(
            "PUT",
            "/api/admin/ai/capabilities/TRANSLATION/order",
            "{\"order\": [\"mistral:mistral-small-latest\"]}");
    assertThat(res.statusCode()).isEqualTo(400);

    JsonNode body = mapper.readTree(send("GET", "/api/admin/ai/implementations", null).body());
    assertThat(body.get("TRANSLATION").get(0).get("id").asText())
        .isEqualTo("mistral:mistral-medium-latest");
  }

  @Test
  void registersALocalImplementation() throws Exception {
    String create =
        """
        {"id": "local:qwen3", "capability": "EMBEDDING", "provider": "openai",
         "model": "Qwen/Qwen3-Embedding-8B", "baseUrl": "http://localhost:11434/v1",
         "endpointPath": "/embeddings", "maxBatchSize": 1, "rank": 5,
         "settings": "{\\"dimensions\\": 1024}"}
        """;
    assertThat(send("POST", "/api/admin/ai/implementations", create).statusCode()).isEqualTo(201);

    JsonNode body = mapper.readTree(send("GET", "/api/admin/ai/implementations", null).body());
    JsonNode local =
        body.get("EMBEDDING").findValues("id").stream()
            .filter(n -> n.asText().equals("local:qwen3"))
            .findFirst()
            .orElse(null);
    assertThat(local).isNotNull();

    // No credential named, so it counts as configured: a machine in the room has no API key.
    JsonNode row =
        body.get("EMBEDDING").findParents("id").stream()
            .filter(n -> n.get("id").asText().equals("local:qwen3"))
            .findFirst()
            .orElseThrow();
    assertThat(row.get("credentialPresent").asBoolean()).isTrue();
  }

  @Test
  void deletingAModelThatProducedStoredOutputIsRefused() throws Exception {
    // The ranking needs the row to decide whether that output beats what replaced it.
    Long archiveId =
        jdbc.queryForObject("INSERT INTO archive (name) VALUES ('T') RETURNING id", Long.class);
    Long recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, lang,
                                metadata_lang, status)
            VALUES (?, 'test', 'ai-admin-1', 'T', 'de', 'de', 'complete') RETURNING id
            """,
            Long.class,
            archiveId);
    Long attachmentId =
        jdbc.queryForObject(
            """
            INSERT INTO attachment (record_id, role, path, mime, bytes, created_at)
            VALUES (?, 'page_image', 'x.jpg', 'image/jpeg', 1, now()) RETURNING id
            """,
            Long.class,
            recordId);
    Long pageId =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            recordId,
            attachmentId);
    jdbc.update(
        """
        INSERT INTO page_translation (page_id, model, text_en, created_at)
        VALUES (?, 'mistral-small-latest', 'cheap', now())
        """,
        pageId);

    HttpResponse<String> res =
        send("DELETE", "/api/admin/ai/implementations/mistral:mistral-small-latest", null);

    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).contains("Disable it instead");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ai_implementation WHERE id = 'mistral:mistral-small-latest'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void anUnusedImplementationCanBeDeleted() throws Exception {
    send(
        "POST",
        "/api/admin/ai/implementations",
        """
        {"id": "custom:unused", "capability": "OCR", "provider": "mistral-batch", "model": "unused-model",
         "baseUrl": "https://example.invalid", "rank": 50}
        """);
    assertThat(send("DELETE", "/api/admin/ai/implementations/custom:unused", null).statusCode())
        .isEqualTo(204);
  }

  @Test
  void servesTheProvidersTheBuildImplements() throws Exception {
    JsonNode body = mapper.readTree(send("GET", "/api/admin/ai/providers", null).body());

    JsonNode providers = body.get("providers");
    assertThat(providers).isNotNull();
    assertThat(providers.findValues("id").stream().map(JsonNode::asText))
        .contains("mistral-batch", "openai");

    // The UI builds its form from this, so each provider must say what it can do and what it
    // needs — without the frontend knowing what any particular provider is.
    JsonNode mistral =
        providers.findParents("id").stream()
            .filter(n -> "mistral-batch".equals(n.path("id").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(mistral.get("batchStyle").asText()).isEqualTo("ASYNC_JOB");
    assertThat(mistral.get("capabilities").has("OCR")).isTrue();
    assertThat(mistral.get("capabilities").get("OCR").get("endpointPath").asText())
        .isEqualTo("/v1/ocr");
    assertThat(mistral.get("capabilities").get("OCR").get("settings").isArray()).isTrue();
  }

  @Test
  void refusesAProviderWithNoAdapter() throws Exception {
    // Free text let a row name any provider while the runtime submitted it through Mistral's
    // batch API regardless, failing every job it claimed.
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "anthropic:claude", "capability": "TRANSLATION", "provider": "anthropic",
             "model": "claude-opus-5", "baseUrl": "https://api.anthropic.com", "rank": 60}
            """);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("anthropic");
  }

  @Test
  void refusesAProviderThatCannotServeThatCapability() throws Exception {
    // There is no Mistral embedding adapter, so a row claiming one would never run.
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "mistral:embed", "capability": "EMBEDDING", "provider": "mistral-batch",
             "model": "mistral-embed", "baseUrl": "https://api.mistral.ai", "rank": 61}
            """);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("EMBEDDING");
  }

  @Test
  void refusesABatchSizeTheProviderCannotHonour() throws Exception {
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "openai:toobig", "capability": "EMBEDDING", "provider": "openai",
             "model": "text-embedding-3-small", "baseUrl": "https://api.openai.com/v1",
             "maxBatchSize": 99999, "rank": 62}
            """);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("maxBatchSize");
  }

  @Test
  void refusesAMissingProvider() throws Exception {
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "nameless:model", "capability": "TRANSLATION", "model": "x",
             "baseUrl": "https://example.invalid", "rank": 63}
            """);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("provider");
  }

  @Test
  void refusesASettingOutsideTheChoicesItsProviderDeclared() throws Exception {
    // The dropdown constrains the form, but a constraint that lives only in the UI is not one.
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "mistral:tiertest", "capability": "TRANSLATION", "provider": "mistral-batch",
             "model": "mistral-tiny", "baseUrl": "https://api.mistral.ai", "rank": 64,
             "settings": "{\\"tier\\": \\"premium\\"}"}
            """);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("tier");
    assertThat(response.body()).contains("bulk");
  }

  @Test
  void acceptsADeclaredChoice() throws Exception {
    var response =
        send(
            "POST",
            "/api/admin/ai/implementations",
            """
            {"id": "mistral:tierok", "capability": "TRANSLATION", "provider": "mistral-batch",
             "model": "mistral-tiny-2", "baseUrl": "https://api.mistral.ai", "rank": 65,
             "settings": "{\\"tier\\": \\"best\\"}"}
            """);

    assertThat(response.statusCode()).isEqualTo(201);
  }

  @Test
  void requiresAdmin() throws Exception {
    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/ai/implementations"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isIn(401, 403);
  }
}
