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
 *
 * <p>Zero scopes has a further consequence beyond just "nothing to check": Spring's default consent
 * flow requires at least one approved scope to succeed at all --
 * OAuth2AuthorizationConsentAuthenticationProvider throws {@code access_denied} when the
 * consented-scopes set is empty and there's no prior consent, which is unconditionally true for a
 * scopeless client. This is why {@code registeredClientRepository} in McpAuthorizationServerConfig
 * sets {@code requireAuthorizationConsent(false)} specifically for scopeless (DCR) clients --
 * discovered when the real Claude.ai flow reproducibly failed at the consent-accept step ({@code
 * confidentialDcrClientCanCompleteAuthorizeAndConsent} below reproduces this with a real DCR-issued
 * {@code client_secret_post} client, matching Claude.ai's exact registration shape).
 *
 * <p>The main round trip also sends an RFC 8707 {@code resource} parameter on both the authorize
 * and token requests -- required for the issued JWT's {@code aud} claim to be set to anything other
 * than the client_id (see McpAuthorizationServerConfig's explicit {@code tokenGenerator} bean,
 * needed because the library's own audience-aware generator never got applied due to yet another
 * init()-ordering race). This test is the first in the whole plan to ever exercise a real issued
 * token against McpResourceServerConfig's own audience validation -- every earlier test either
 * registered a client directly (bypassing DCR and the consent step entirely) or hit the
 * consent-required assumption-skip before reaching token exchange.
 *
 * <p>The {@code resource} value used here is the FULL MCP endpoint URL ({@code .../api/mcp/sse}),
 * not just its path prefix -- matching what Claude.ai itself sends (confirmed via its own RFC 9728
 * protected-resource-metadata probe at {@code /.well-known/oauth-protected-resource/api/mcp/sse} in
 * production nginx logs), and exactly what surfaced the next live bug: McpResourceServerConfig
 * originally configured {@code resourcePath("/api/mcp")} (the route prefix), not {@code
 * "/api/mcp/sse"} (the actual endpoint) -- so every real, correctly-issued token still failed
 * audience validation, because the validator's own expected-audience string never matched what a
 * real client actually requested.
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
    // RFC 8707 resource parameter -- ResourceIdentifierAudienceTokenCustomizer (bundled with the
    // MCP authorization-server library, confirmed via decompilation) only sets the issued JWT's
    // `aud` claim when the token request carries this parameter; without it, `aud` falls back to
    // Spring's own default (client_id), which McpResourceServerConfig's audience validation then
    // rejects with "aud claim is not valid". Sending it is the CLIENT's responsibility per spec --
    // a real MCP client (Claude.ai included) is expected to include it once it reaches this point
    // in the flow, which nothing tested before this task had actually reached.
    String resource = java.net.URLEncoder.encode(base() + "/api/mcp/sse", "UTF-8");

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
                + "&resource="
                + resource
                + "&code_challenge="
                + codeChallenge()
                + "&code_challenge_method=S256&state=test-state");
    var authorizeRequest =
        HttpRequest.newBuilder(authorizeUri)
            .header("X-Auth-Email", ALLOWLISTED_EMAIL)
            .GET()
            .build();
    var authorizeResponse = http.send(authorizeRequest, HttpResponse.BodyHandlers.ofString());

    // Scopeless DCR clients now get requireAuthorizationConsent(false) (see
    // McpAuthorizationServerConfig.skipConsentForScopelessClients), so this must redirect
    // straight through with a code, not show a consent page -- confirmed working by
    // confidentialDcrClientCanCompleteAuthorizeAndConsent above; a consent page here would mean
    // that fix regressed.
    String location = authorizeResponse.headers().firstValue("Location").orElse("");
    assertThat(authorizeResponse.statusCode())
        .as("scopeless DCR client must skip consent and redirect straight through: " + location)
        .isEqualTo(302);
    assertThat(location)
        .as("redirect must carry an authorization code, not an OAuth error (" + location + ")")
        .contains("code=");

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
            + "&resource="
            + resource
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

    // Regression check for a live bug: the issued JWT's `aud` claim used to come back set to the
    // client_id (Spring's plain default JWT customizer) rather than the resource identifier,
    // because McpAuthorizationServerConfigurer's own audience-aware OAuth2TokenGenerator never
    // actually got applied -- an init()-ordering race (same class of bug as the DCR
    // AuthenticationProvider gap above), fixed by defining OAuth2TokenGenerator as an explicit
    // bean in McpAuthorizationServerConfig instead of relying on the library's internal wiring.
    String[] jwtParts = accessToken.split("\\.");
    Map<String, Object> claims =
        mapper.readValue(Base64.getUrlDecoder().decode(jwtParts[1]), Map.class);
    assertThat(claims.get("aud"))
        .as("issued token's audience must be the resource identifier, not the client_id")
        .isEqualTo(java.net.URLDecoder.decode(resource, "UTF-8"));

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

    // Real MCP clients (Claude.ai confirmed) also probe with a plain GET before falling back to
    // this app's actual (POST-only, Streamable HTTP) transport. A valid, correctly-audienced
    // token must never be rejected by the SECURITY layer regardless of HTTP method -- a 404 here
    // (no GET handler registered, since this transport is POST-only) is fine; a 401/403 would mean
    // the audience validation is STILL broken for this exact request shape, which is exactly what
    // regressed in production (resourcePath mismatch -- see McpResourceServerConfig).
    var getMcpRequest =
        HttpRequest.newBuilder(URI.create(base() + "/api/mcp/sse"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var getMcpResponse = http.send(getMcpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(getMcpResponse.statusCode())
        .as("a validly-audienced token must never be rejected by the security layer on GET")
        .isNotIn(401, 403);
  }

  // Regression test for a live production bug found during Task 10's manual Claude.ai
  // verification: the real Claude.ai client registers via DCR with
  // token_endpoint_auth_method=client_secret_post (a confidential client) -- unlike
  // fullDcrAuthorizeTokenAndToolCallRoundTrip above, which uses "none" (a public client). The
  // real flow's consent-accept POST failed with an OAuth error redirect
  // (error=access_denied&error_description=OAuth+2.0+Parameter%3A+client_id) instead of a
  // code=... redirect -- reproduced here with a real DCR-issued client_secret_post client to
  // isolate whether the client's auth method is what triggers it.
  @SuppressWarnings("unchecked")
  @Test
  void confidentialDcrClientCanCompleteAuthorizeAndConsent() throws Exception {
    String dcrBody =
        mapper.writeValueAsString(
            Map.of(
                "client_name", "confidential-test-client",
                "redirect_uris", java.util.List.of(REDIRECT_URI),
                "grant_types", java.util.List.of("authorization_code", "refresh_token"),
                "response_types", java.util.List.of("code"),
                "token_endpoint_auth_method", "client_secret_post"));
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

    // Scopeless DCR clients now get requireAuthorizationConsent(false) (see
    // McpAuthorizationServerConfig.skipConsentForScopelessClients), so this must redirect
    // straight through with a code, not show a consent page.
    assertThat(authorizeResponse.statusCode())
        .as("scopeless DCR client must skip consent and redirect straight through")
        .isEqualTo(302);
    String directLocation = authorizeResponse.headers().firstValue("Location").orElse("");
    assertThat(directLocation)
        .as(
            "redirect must carry an authorization code, not an OAuth error ("
                + directLocation
                + ")")
        .contains("code=");
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
