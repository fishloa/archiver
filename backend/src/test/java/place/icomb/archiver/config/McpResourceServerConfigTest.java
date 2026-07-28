package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class McpResourceServerConfigTest {

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
  }

  @Test
  void mcpEndpointRejectsRequestsWithNoToken() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/sse"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isIn(401, 403);
  }

  @Test
  void mcpEndpointRejectsAGarbageBearerToken() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/sse"))
            .header("Authorization", "Bearer not-a-real-jwt")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isIn(401, 403);
  }

  // Regression test for a live production bug: an unauthenticated request to the RFC 9728
  // protected-resource-metadata document was returning 403 rather than serving the document.
  // Root cause was mcpResourceServerSecurityFilterChain's securityMatcher covering only
  // "/api/mcp/**" -- Spring's own OAuth2ProtectedResourceMetadataFilter is registered as part of
  // THIS chain's filters (via McpServerOAuth2Configurer), but a chain's filters only run for
  // requests that first match its own securityMatcher, so requests to the metadata path fell
  // through to the main application's deny-by-default chain instead of ever reaching the filter
  // that serves it. MCP clients (including Claude.ai) fetch this document, unauthenticated, to
  // discover which authorization server protects a resource -- if it 403s, the client never
  // learns how to authenticate at all.
  @Test
  void protectedResourceMetadataIsPubliclyReadable() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/oauth-protected-resource"))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode())
        .as("protected-resource metadata must be readable without authentication")
        .isEqualTo(200);
  }

  // Some MCP clients (Claude.ai among them) probe this document per RFC 9728's path-appended
  // convention: /.well-known/oauth-protected-resource + the exact resource path they're calling,
  // not just the bare document. This must be routed to the same chain too, even if it 404s past
  // that point (a real 404 from inside the chain is fine; falling through to the wrong chain and
  // 403ing is not).
  @Test
  void pathAppendedProtectedResourceMetadataIsNotBlockedByTheWrongChain() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/.well-known/oauth-protected-resource/api/mcp/sse"))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode())
        .as("must not be denied by the main application's deny-by-default chain")
        .isNotEqualTo(403);
  }

  @Test
  void oldSharedMcpTokenFilterNoLongerExists() {
    org.junit.jupiter.api.Assertions.assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("place.icomb.archiver.config.McpTokenFilter"));
  }
}
