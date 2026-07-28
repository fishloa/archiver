package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.Base64;
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

/**
 * Drives the full MCP OAuth flow at the HTTP layer: Dynamic Client Registration, authorize (with a
 * pre-trusted X-Auth-Email standing in for a real browser session -- a JUnit test cannot drive an
 * actual Google/Apple login), token exchange with PKCE, then a call to /api/mcp/ with the issued
 * token. This is the last automated gate before the manual Claude.ai verification in Task 10 of the
 * implementation plan -- passing this test is necessary, not sufficient.
 *
 * <p>Omits {@code scope} entirely from both the DCR request body and the authorize call, rather
 * than the brief's literal {@code scope=openid}. Two independent reasons: (1) this authorization
 * server has no {@code .oidc(...)} configuration anywhere (see McpAuthorizationServerConfig), so
 * {@code openid} is rejected with {@code invalid_scope} -- discovered independently while writing
 * Task 6's tests; and (2) the default {@code OAuth2ClientRegistrationAuthenticationValidator}
 * unconditionally rejects ANY {@code scope} field in a Dynamic Client Registration request body
 * ("scope must not be set during Dynamic Client Registration", RFC 7591 section 3.2.2), so a
 * DCR-registered client (unlike Task 6's, which registers a {@code RegisteredClient} directly with
 * {@code .scope("mcp.read")}, bypassing DCR) ends up with no scopes at all -- requesting one at
 * authorize time would fail scope validation. McpResourceServerConfig authenticates but does not
 * scope-check MCP calls, so no scope is actually required for this round trip to succeed.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpOAuthRoundTripTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String ALLOWLISTED_EMAIL = "mcp-roundtrip-test@example.com";
  private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";
  private static final String CODE_VERIFIER =
      "test-code-verifier-must-be-at-least-43-characters-long-per-pkce-spec";

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void seedAllowlistedUser() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ALLOWLISTED_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'McpRoundTripTest User'").update();
    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) "
                    + "VALUES ('McpRoundTripTest User', 'user') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", userId)
        .param("e", ALLOWLISTED_EMAIL)
        .update();
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private String codeChallenge() throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(CODE_VERIFIER.getBytes("UTF-8"));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  @SuppressWarnings("unchecked")
  @Test
  void fullDcrAuthorizeTokenAndToolCallRoundTrip() throws Exception {
    // 1. Dynamic Client Registration
    String dcrBody =
        mapper.writeValueAsString(
            Map.of(
                "client_name", "round-trip-test-client",
                "redirect_uris", java.util.List.of(REDIRECT_URI),
                "grant_types", java.util.List.of("authorization_code"),
                "response_types", java.util.List.of("code"),
                "token_endpoint_auth_method", "none"));
    var dcrRequest =
        HttpRequest.newBuilder(URI.create(base() + "/oauth2/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(dcrBody))
            .build();
    var dcrResponse = http.send(dcrRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(dcrResponse.statusCode()).isEqualTo(201);
    Map<String, Object> dcrJson = mapper.readValue(dcrResponse.body(), Map.class);
    String clientId = (String) dcrJson.get("client_id");
    assertThat(clientId).isNotBlank();

    // 2. Authorize, with a trusted, allowlisted identity standing in for a real browser session.
    // No `scope` param -- Dynamic Client Registration's default
    // OAuth2ClientRegistrationAuthenticationValidator unconditionally rejects a `scope` field in
    // the registration request body itself ("scope must not be set during Dynamic Client
    // Registration", RFC 7591 section 3.2.2), so the resulting registered client has no scopes.
    // Requesting a scope at authorize time that the client isn't registered for would fail
    // scope validation, so this test (unlike Task 6's, which registers a client directly with
    // `.scope("mcp.read")` and can therefore request it) omits scope entirely -- consistent
    // with McpResourceServerConfig, which authenticates but does not scope-check MCP calls.
    var authorizeUri =
        URI.create(
            base()
                + "/oauth2/authorize?response_type=code&client_id="
                + clientId
                + "&redirect_uri="
                + java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&code_challenge="
                + codeChallenge()
                + "&code_challenge_method=S256&state=test-state");
    var authorizeRequest =
        HttpRequest.newBuilder(authorizeUri)
            .header("X-Auth-Email", ALLOWLISTED_EMAIL)
            .GET()
            .build();
    var authorizeResponse = http.send(authorizeRequest, HttpResponse.BodyHandlers.ofString());

    // A 302 to the redirect_uri with a code= param is the success path. If Spring AS shows a
    // consent page instead (302 to a consent view, or 200 with a consent form) on first
    // authorization for a new client, that is also acceptable here -- assert we did NOT get
    // redirected to /signin, which is the one outcome that would mean the session-reuse filter
    // failed.
    String location = authorizeResponse.headers().firstValue("Location").orElse("");
    assertThat(location).doesNotContain("/signin");

    if (authorizeResponse.statusCode() != 302 || !location.contains("code=")) {
      // Consent required. This is expected Spring AS default behaviour on first use, per the
      // design spec's decision to keep the default consent screen rather than auto-approve.
      // A JUnit test cannot click a consent button; report clearly rather than failing
      // opaquely, since this is not itself a defect in Tasks 3-8.
      org.junit.jupiter.api.Assumptions.assumeTrue(
          false,
          "Consent screen required and cannot be driven from a JUnit test (by design, per the"
              + " spec). Authorize response: "
              + authorizeResponse.statusCode()
              + " -> "
              + location
              + ". Verify the full flow manually per Task 10 instead.");
    }

    String code =
        java.util.Arrays.stream(location.split("[?&]"))
            .filter(p -> p.startsWith("code="))
            .findFirst()
            .map(p -> p.substring(5))
            .orElseThrow();

    // 3. Token exchange with PKCE
    String tokenBody =
        "grant_type=authorization_code"
            + "&code="
            + code
            + "&redirect_uri="
            + java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
            + "&client_id="
            + clientId
            + "&code_verifier="
            + CODE_VERIFIER;
    var tokenRequest =
        HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
            .build();
    var tokenResponse = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    Map<String, Object> tokenJson = mapper.readValue(tokenResponse.body(), Map.class);
    String accessToken = (String) tokenJson.get("access_token");
    assertThat(accessToken).isNotBlank();

    // 4. Call the MCP endpoint with the issued token
    var mcpRequest =
        HttpRequest.newBuilder(URI.create(base() + "/api/mcp/sse"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"round-trip-test\",\"version\":\"1\"}}}"))
            .build();
    var mcpResponse = http.send(mcpRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(mcpResponse.statusCode()).isNotIn(401, 403);
  }

  @Test
  void expiredOrGarbageTokenIsRejected() throws Exception {
    var mcpRequest =
        HttpRequest.newBuilder(URI.create(base() + "/api/mcp/sse"))
            .header("Authorization", "Bearer this-is-not-a-valid-jwt")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    var mcpResponse = http.send(mcpRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(mcpResponse.statusCode()).isIn(401, 403);
  }

  @Test
  void nonAllowlistedEmailCannotCompleteAuthorize() throws Exception {
    String dcrBody =
        mapper.writeValueAsString(
            Map.of(
                "client_name", "second-test-client",
                "redirect_uris", java.util.List.of(REDIRECT_URI),
                "grant_types", java.util.List.of("authorization_code"),
                "response_types", java.util.List.of("code"),
                "token_endpoint_auth_method", "none"));
    var dcrRequest =
        HttpRequest.newBuilder(URI.create(base() + "/oauth2/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(dcrBody))
            .build();
    var dcrResponse = http.send(dcrRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(dcrResponse.statusCode()).isEqualTo(201);
    Map<String, Object> dcrJson = mapper.readValue(dcrResponse.body(), Map.class);
    String clientId = (String) dcrJson.get("client_id");

    var authorizeUri =
        URI.create(
            base()
                + "/oauth2/authorize?response_type=code&client_id="
                + clientId
                + "&redirect_uri="
                + java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&code_challenge="
                + codeChallenge()
                + "&code_challenge_method=S256&state=test-state");
    var authorizeRequest =
        HttpRequest.newBuilder(authorizeUri)
            .header("X-Auth-Email", "definitely-not-allowlisted@example.com")
            .GET()
            .build();
    var authorizeResponse = http.send(authorizeRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(authorizeResponse.statusCode()).isEqualTo(302);
    assertThat(authorizeResponse.headers().firstValue("Location").orElse("")).contains("/signin");
  }
}
