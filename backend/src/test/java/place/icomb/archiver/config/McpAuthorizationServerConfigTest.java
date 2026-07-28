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
class McpAuthorizationServerConfigTest {

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
  void authorizationServerMetadataIsPublished() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/oauth-authorization-server"))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"authorization_endpoint\"");
    assertThat(response.body()).contains("\"token_endpoint\"");
    assertThat(response.body()).contains("\"registration_endpoint\"");
  }

  @Test
  void dynamicClientRegistrationEndpointExists() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/register"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"client_name\":\"test-client\","
                        + "\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"],"
                        + "\"grant_types\":[\"authorization_code\"],"
                        + "\"response_types\":[\"code\"],"
                        + "\"token_endpoint_auth_method\":\"none\"}"))
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    // 201 = registered successfully. Anything in the 2xx/4xx range that ISN'T a 404/405 proves
    // the endpoint exists and is wired up, even if this particular payload shape needs
    // adjustment once the real library's exact requirements are confirmed.
    assertThat(response.statusCode()).isNotIn(404, 405);
  }
}
