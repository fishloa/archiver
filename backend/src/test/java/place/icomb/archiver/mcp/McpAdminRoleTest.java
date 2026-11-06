package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An MCP call carries the role its user holds in {@code app_user}. The MCP server is disabled in
 * the test profile, so a probe endpoint under {@code /api/mcp/} reports what the MCP security chain
 * authenticated, using a token issued through the real DCR, authorize and token flow.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(McpAdminRoleTest.Probe.class)
class McpAdminRoleTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";
  private static final String CODE_VERIFIER =
      "test-code-verifier-must-be-at-least-43-characters-long-per-pkce-spec";

  @TestConfiguration
  @RestController
  static class Probe {
    @GetMapping("/api/mcp/test-roles")
    List<String> roles() {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
  }

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

  @Test
  void anAdministratorsMcpTokenCarriesTheAdminRole() throws Exception {
    String token = tokenFor(seedUser("mcp-admin-role-admin@example.com", "admin"));
    assertThat(rolesSeenBy(token)).contains("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void anOrdinaryUsersMcpTokenDoesNot() throws Exception {
    String token = tokenFor(seedUser("mcp-admin-role-user@example.com", "user"));
    assertThat(rolesSeenBy(token)).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
  }

  @Test
  void promotionTakesEffectWithoutANewToken() throws Exception {
    String email = "mcp-admin-role-promoted@example.com";
    String token = tokenFor(seedUser(email, "user"));
    assertThat(rolesSeenBy(token)).doesNotContain("ROLE_ADMIN");

    jdbc.sql(
            "UPDATE app_user SET role = 'admin' WHERE id = "
                + "(SELECT user_id FROM app_user_email WHERE email = :e)")
        .param("e", email)
        .update();

    assertThat(rolesSeenBy(token)).contains("ROLE_ADMIN");
  }

  private String seedUser(String email, String role) {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", email).update();
    Long id =
        jdbc.sql("INSERT INTO app_user (display_name, role) VALUES (:n, :r) RETURNING id")
            .param("n", email)
            .param("r", role)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", id)
        .param("e", email)
        .update();
    return email;
  }

  @SuppressWarnings("unchecked")
  private List<String> rolesSeenBy(String token) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/mcp/test-roles"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readValue(response.body(), List.class);
  }

  @SuppressWarnings("unchecked")
  private String tokenFor(String email) throws Exception {
    String dcrBody =
        mapper.writeValueAsString(
            Map.of(
                "client_name", "mcp-admin-role-test",
                "redirect_uris", List.of(REDIRECT_URI),
                "grant_types", List.of("authorization_code"),
                "response_types", List.of("code"),
                "token_endpoint_auth_method", "none"));
    var dcr =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/oauth2/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(dcrBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(dcr.statusCode()).isEqualTo(201);
    String clientId = (String) mapper.readValue(dcr.body(), Map.class).get("client_id");

    var authorize =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        base()
                            + "/oauth2/authorize?response_type=code&client_id="
                            + clientId
                            + "&redirect_uri="
                            + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                            + "&code_challenge="
                            + codeChallenge()
                            + "&code_challenge_method=S256&state=s"))
                .header("X-Auth-Email", email)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String location = authorize.headers().firstValue("Location").orElse("");
    assertThat(location).as(location).contains("code=");
    String code =
        Arrays.stream(location.split("[?&]"))
            .filter(p -> p.startsWith("code="))
            .findFirst()
            .map(p -> p.substring(5))
            .orElseThrow();

    var token =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&code="
                            + code
                            + "&redirect_uri="
                            + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                            + "&client_id="
                            + clientId
                            + "&code_verifier="
                            + CODE_VERIFIER))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(token.statusCode()).isEqualTo(200);
    return (String) mapper.readValue(token.body(), Map.class).get("access_token");
  }

  private String codeChallenge() throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(CODE_VERIFIER.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private String base() {
    return "http://localhost:" + port;
  }
}
