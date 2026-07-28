package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTokenFilterTest {

  private static final String TOKEN = "test-mcp-token";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.mcp.token", () -> TOKEN);
  }

  private HttpResponse<String> postMcp(String bearer) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/sse"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));
    if (bearer != null) {
      builder = builder.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMcpRequestWithNoToken() throws Exception {
    assertThat(postMcp(null).statusCode()).isIn(401, 403);
  }

  @Test
  void rejectsMcpRequestWithWrongToken() throws Exception {
    assertThat(postMcp("not-the-token").statusCode()).isIn(401, 403);
  }

  @Test
  void acceptsMcpRequestWithCorrectToken() throws Exception {
    assertThat(postMcp(TOKEN).statusCode()).isNotIn(401, 403);
  }
}

/**
 * End-to-end proof of the fail-closed behaviour of the empty default for {@code
 * archiver.mcp.token}: with no token configured, {@code /api/mcp/**} refuses both an empty and an
 * arbitrary presented bearer token.
 *
 * <p>Deliberately a separate Spring context from {@link McpTokenFilterTest} — it must NOT set
 * {@code archiver.mcp.token}, so the property falls through to its empty default from {@code
 * application.yml}.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTokenFilterEmptyDefaultTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // archiver.mcp.token is intentionally left unset here so it falls back to its empty default.
  }

  private HttpResponse<String> postMcp(String bearer) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/sse"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));
    if (bearer != null) {
      builder = builder.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void emptyConfiguredTokenRejectsEmptyPresentedToken() throws Exception {
    assertThat(postMcp("").statusCode()).isIn(401, 403);
  }

  @Test
  void emptyConfiguredTokenRejectsArbitraryPresentedToken() throws Exception {
    assertThat(postMcp("some-arbitrary-token").statusCode()).isIn(401, 403);
  }
}

/**
 * Unit-level proof that {@link McpTokenFilter} itself never authenticates when constructed with a
 * blank token — independent of the HTTP stack.
 *
 * <p>The full end-to-end tests in {@link McpTokenFilterEmptyDefaultTest} send an HTTP request with
 * an empty bearer token, but servlet containers trim trailing OWS from header values on the wire,
 * so {@code "Bearer " + ""} arrives server-side as the header value {@code "Bearer"} (no trailing
 * space) — which never satisfies {@code startsWith("Bearer ")} regardless of whether the filter's
 * blank-token guard exists. That means the HTTP-level test cannot, by itself, distinguish the
 * guarded implementation from a regressed one that dropped the {@code !mcpToken.isBlank()} check.
 * This test calls {@link McpTokenFilter#doFilterInternal} directly with an in-memory header value
 * of exactly {@code "Bearer "} (empty presented token), bypassing header transport entirely, so it
 * genuinely fails if the blank-token guard is removed.
 */
class McpTokenFilterGuardUnitTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void blankConfiguredTokenNeverAuthenticatesEmptyPresentedToken() throws Exception {
    assertNeverAuthenticates("");
  }

  @Test
  void blankConfiguredTokenNeverAuthenticatesArbitraryPresentedToken() throws Exception {
    assertNeverAuthenticates("some-arbitrary-token");
  }

  private void assertNeverAuthenticates(String presentedToken) throws Exception {
    SecurityContextHolder.clearContext();
    McpTokenFilter filter = new McpTokenFilter("");

    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + presentedToken);

    filter.doFilterInternal(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(request, response);
  }
}
