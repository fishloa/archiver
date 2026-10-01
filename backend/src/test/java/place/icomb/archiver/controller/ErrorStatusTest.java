package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A client's mistake is a 4xx with an {@code error} body, not a 500: an unknown URL, a wrong
 * method, a body that is not JSON, a path segment of the wrong type, a media type the endpoint does
 * not take.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorStatusTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private static final String ADMIN_EMAIL = "error-status-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'ErrorStatusAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('ErrorStatusAdmin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  private HttpResponse<String> send(String method, String path, String contentType, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api" + path))
            .header("X-Auth-Email", ADMIN_EMAIL);
    if (contentType != null) {
      b.header("Content-Type", contentType);
    }
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertStatus(HttpResponse<String> resp, int status) throws Exception {
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
    assertThat(json.readTree(resp.body()).has("error")).as(resp.body()).isTrue();
  }

  @Test
  void anUnknownUrlIsNotFound() throws Exception {
    assertStatus(send("GET", "/admin/no-such-thing", null, null), 404);
  }

  @Test
  void aWrongMethodIsMethodNotAllowed() throws Exception {
    assertStatus(send("GET", "/admin/pages/1/corrections", null, null), 405);
  }

  @Test
  void aBodyThatIsNotJsonIsABadRequest() throws Exception {
    assertStatus(send("POST", "/admin/pages/1/corrections", "application/json", "{not json"), 400);
  }

  @Test
  void aPathSegmentOfTheWrongTypeIsABadRequest() throws Exception {
    assertStatus(send("POST", "/admin/pages/abc/corrections", "application/json", "{}"), 400);
  }

  @Test
  void aMediaTypeTheEndpointDoesNotTakeIsRefused() throws Exception {
    assertStatus(send("POST", "/admin/pages/1/corrections", "text/plain", "x"), 415);
  }
}
