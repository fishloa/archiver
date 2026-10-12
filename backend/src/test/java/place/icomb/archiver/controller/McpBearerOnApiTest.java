package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Date;
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
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportWorker;

/**
 * An MCP user's access token is good on the UI's own routes: read anything the UI reads, ask for a
 * PDF and download it. It is not good for anything else, and a token that is wrong in any way gets
 * nothing. Tokens here are signed with the application's own key, as the authorization server signs
 * them.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBearerOnApiTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private static final String ISSUER = "https://archive.czernin.eu";
  private static final String USER = "mcp-reader@example.com";
  private static final String ADMIN = "mcp-admin@example.com";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;
  @Autowired private PdfExportWorker worker;
  @Autowired private JWKSource<SecurityContext> jwkSource;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private long record;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    for (String email : new String[] {USER, ADMIN}) {
      jdbc.update("DELETE FROM app_user_email WHERE email = ?", email);
    }
    jdbc.update("DELETE FROM app_user WHERE display_name IN ('McpReader', 'McpAdmin')");
    addUser("McpReader", "user", USER);
    addUser("McpAdmin", "admin", ADMIN);
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Magistrat file");
    for (int seq = 1; seq <= 3; seq++) {
      PdfFixtures.page(jdbc, storageRoot, record, seq, "English " + seq);
    }
  }

  private void addUser(String name, String role, String email) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES (?, ?) RETURNING id",
            Long.class,
            name,
            role);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, email);
  }

  private String token(String subject, String audience, long expiresInSeconds) throws Exception {
    RSAKey key =
        (RSAKey) jwkSource.get(new JWKSelector(new JWKMatcher.Builder().build()), null).get(0);
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(audience)
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private String good(String subject) throws Exception {
    return token(subject, ISSUER + "/api/mcp/sse", 600);
  }

  private HttpResponse<byte[]> call(String method, String path, String bearer, String body)
      throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
    if (bearer != null) {
      req.header("Authorization", "Bearer " + bearer);
    }
    req.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void anMcpTokenReadsWhatTheUiReads() throws Exception {
    HttpResponse<byte[]> doc = call("GET", "/api/v1/documents/" + record, good(USER), null);
    assertThat(doc.statusCode()).as(new String(doc.body())).isEqualTo(200);
    assertThat(json.readTree(doc.body()).get("pages")).hasSize(3);

    assertThat(call("GET", "/api/v1/search?q=Magistrat", good(USER), null).statusCode())
        .isEqualTo(200);
  }

  @Test
  void anMcpTokenCanRequestAPageRangeAndDownloadThePdf() throws Exception {
    String t = good(USER);
    HttpResponse<byte[]> made =
        call(
            "POST",
            "/api/records/" + record + "/pdf-exports",
            t,
            "{\"variant\":\"original\",\"pages\":\"2\"}");
    assertThat(made.statusCode()).as(new String(made.body())).isEqualTo(202);
    JsonNode view = json.readTree(made.body());
    assertThat(view.get("pageCount").asInt()).isEqualTo(1);
    String id = view.get("id").asText();

    worker.runOnce();

    assertThat(
            json.readTree(call("GET", "/api/pdf-exports/" + id, t, null).body())
                .get("state")
                .asText())
        .isEqualTo("ready");
    HttpResponse<byte[]> file = call("GET", "/api/pdf-exports/" + id + "/file", t, null);
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(new String(file.body(), 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void anMcpTokenCannotChangeAnythingElse() throws Exception {
    String t = good(USER);
    assertThat(call("POST", "/api/profile/anything", t, "{}").statusCode()).isEqualTo(403);
    assertThat(call("PUT", "/api/profile", t, "{}").statusCode()).isEqualTo(403);
    assertThat(call("DELETE", "/api/profile/anything", t, null).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/admin/audit", t, null).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/ingest/records", t, "{}").statusCode()).isEqualTo(403);
  }

  @Test
  void anAdministratorsMcpTokenStillDoesNotOpenTheAdminRoutes() throws Exception {
    String t = good(ADMIN);
    assertThat(call("GET", "/api/admin/stats", t, null).statusCode()).isEqualTo(403);
    assertThat(call("GET", "/api/v1/documents/" + record, t, null).statusCode()).isEqualTo(200);
  }

  @Test
  void aTokenThatIsWrongInAnyWayGetsNothing() throws Exception {
    String path = "/api/v1/documents/" + record;
    assertThat(
            call("GET", path, token(USER, "https://elsewhere.example/api/mcp/sse", 600), null)
                .statusCode())
        .as("wrong audience")
        .isIn(401, 403);
    assertThat(call("GET", path, token(USER, ISSUER + "/api/mcp/sse", -60), null).statusCode())
        .as("expired")
        .isIn(401, 403);
    assertThat(call("GET", path, good("stranger@example.com"), null).statusCode())
        .as("not on the allowlist")
        .isIn(401, 403);
    assertThat(call("GET", path, good(USER).substring(0, 40) + "x.y", null).statusCode())
        .as("mangled")
        .isIn(401, 403);
    assertThat(call("GET", path, "not.a.token", null).statusCode()).as("junk").isIn(401, 403);
    assertThat(call("GET", path, null, null).statusCode()).as("none").isIn(401, 403);
  }
}
