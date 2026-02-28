package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTest {

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
  private final ObjectMapper mapper = new ObjectMapper();

  private static final String TEST_EMAIL = "test-auth@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private String base() {
    return "http://localhost:" + port + "/api";
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :email").param("email", TEST_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE id NOT IN (SELECT DISTINCT user_id FROM app_user_email)")
        .update();

    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('Auth Test User', 'user')"
                    + " RETURNING id")
            .query(Long.class)
            .single();

    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:userId, :email)")
        .param("userId", userId)
        .param("email", TEST_EMAIL)
        .update();
  }

  @Test
  void meWithoutAuthReturnsUnauthenticated() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/auth/me")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("authenticated")).isEqualTo(false);
  }

  @Test
  void meWithAuthReturnsUserInfo() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/auth/me"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("authenticated")).isEqualTo(true);
    assertThat(body.get("email")).isEqualTo(TEST_EMAIL);
    assertThat(body.get("displayName")).isEqualTo("Auth Test User");
    assertThat(body.get("role")).isEqualTo("user");
    assertThat(body).containsKey("familyTreePersonId");
  }

  @Test
  void meReturnsLangField() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/auth/me"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsKey("lang");
  }

  @Test
  void meReflectsFamilyTreePersonIdAfterProfileUpdate() throws Exception {
    // Set familyTreePersonId via profile PUT
    var putReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 1}"))
            .build();
    var putResp = http.send(putReq, HttpResponse.BodyHandlers.ofString());
    assertThat(putResp.statusCode()).isEqualTo(200);

    // Check /auth/me reflects it
    var meReq =
        HttpRequest.newBuilder(URI.create(base() + "/auth/me"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var meResp = http.send(meReq, HttpResponse.BodyHandlers.ofString());

    assertThat(meResp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(meResp.body(), Map.class);
    assertThat(body.get("authenticated")).isEqualTo(true);
    assertThat(body.get("familyTreePersonId")).isEqualTo(1);
  }

  @Test
  void meWithUnknownEmailReturnsUnauthenticated() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/auth/me"))
            .header("X-Auth-Email", "nobody@example.com")
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("authenticated")).isEqualTo(false);
  }
}
