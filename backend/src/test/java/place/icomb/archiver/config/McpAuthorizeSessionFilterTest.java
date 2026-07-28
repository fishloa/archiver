package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAuthorizeSessionFilterTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String ALLOWLISTED_EMAIL = "mcp-authorize-test@example.com";
  private static final String TEST_CLIENT_ID = "test";
  private static final String TEST_REDIRECT_URI = "https://example.com/callback";

  // The MCP authorization server configurer requires PKCE unconditionally (not just for public
  // clients), per MCP's own OAuth guidance -- so every /oauth2/authorize request in this test
  // carries a code_challenge, even though this test never completes the flow far enough to need
  // the matching code_verifier.
  private static final String TEST_CODE_VERIFIER =
      "test-code-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
  private static final String TEST_CODE_CHALLENGE = sha256Base64Url(TEST_CODE_VERIFIER);

  private static String sha256Base64Url(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;

  @Autowired private RegisteredClientRepository registeredClientRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void seedAllowlistedUser() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ALLOWLISTED_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'McpAuthorizeTest User'").update();
    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) "
                    + "VALUES ('McpAuthorizeTest User', 'user') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", userId)
        .param("e", ALLOWLISTED_EMAIL)
        .update();
  }

  // The authorization endpoint validates client_id against the registered client repository
  // BEFORE authentication is ever checked (OAuth2AuthorizationCodeRequestValidatingFilter runs
  // ahead of ExceptionTranslationFilter in the Authorization Server's filter chain) -- an
  // unregistered client_id fails validation and short-circuits to /error regardless of whether
  // the caller is signed in. A real registered client is required so requests actually reach the
  // authentication check this filter exists to satisfy.
  //
  // Scope is deliberately NOT "openid": that same early validating filter has its own
  // unconditional check ("OpenID Connect 1.0 authentication requests are restricted") that
  // rejects any openid-scoped request unless the caller is ALREADY authenticated when this very
  // early filter runs -- which is before every authentication filter in the chain, including
  // this one. That check has nothing to do with the session-reuse behaviour under test, so a
  // plain (non-OIDC) scope is used instead to reach the actual authentication enforcement
  // further down the chain (ExceptionTranslationFilter / AuthorizationFilter / the real
  // OAuth2AuthorizationEndpointFilter).
  @BeforeEach
  void seedRegisteredClient() {
    if (registeredClientRepository.findByClientId(TEST_CLIENT_ID) == null) {
      RegisteredClient client =
          RegisteredClient.withId(TEST_CLIENT_ID)
              .clientId(TEST_CLIENT_ID)
              .clientSecret("{noop}test-secret")
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri(TEST_REDIRECT_URI)
              .scope("mcp.read")
              .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
              .build();
      registeredClientRepository.save(client);
    }
  }

  @Test
  void allowlistedEmailFromTrustedPeerIsAuthenticatedWithNoRedirectToSignin() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/oauth2/authorize?response_type=code&client_id=test&scope=mcp.read"
                        + "&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"
                        + "&code_challenge="
                        + TEST_CODE_CHALLENGE
                        + "&code_challenge_method=S256"))
            .header("X-Auth-Email", ALLOWLISTED_EMAIL)
            .GET()
            .build();
    var response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

    // Loopback (the test client) is in the default trusted CIDR set, same as every other
    // existing auth test in this codebase -- see application-test.yml / TrustedPeerResolver.
    // With the registered test client and a valid PKCE challenge, an allowlisted caller
    // completes the authorization request -- a 302 redirect back to the client's redirect_uri
    // carrying an authorization code, NOT a redirect to /signin. That distinction (redirect
    // target, not status code) is what proves the session-reuse filter authenticated the caller
    // before Spring Security's own entry point ever had a chance to fire.
    boolean redirectsToSignin =
        response.statusCode() == 302
            && response.headers().firstValue("Location").orElse("").contains("/signin");
    assertThat(redirectsToSignin)
        .as("allowlisted, trusted-peer caller must not be bounced to /signin")
        .isFalse();
  }

  @Test
  void noEmailHeaderRedirectsToSignin() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/oauth2/authorize?response_type=code&client_id=test&scope=mcp.read"
                        + "&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"
                        + "&code_challenge="
                        + TEST_CODE_CHALLENGE
                        + "&code_challenge_method=S256"))
            .GET()
            .build();
    var response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElse("")).contains("/signin");
  }

  @Test
  void nonAllowlistedEmailRedirectsToSigninRatherThanAuthenticating() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/oauth2/authorize?response_type=code&client_id=test&scope=mcp.read"
                        + "&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"
                        + "&code_challenge="
                        + TEST_CODE_CHALLENGE
                        + "&code_challenge_method=S256"))
            .header("X-Auth-Email", "not-on-the-allowlist@example.com")
            .GET()
            .build();
    var response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElse("")).contains("/signin");
  }
}
