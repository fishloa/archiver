package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
class AdminControllerTest {

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

  private static final String TEST_EMAIL = "admin-test@example.com";
  private static final String TEST_EMAIL_2 = "admin-test-2@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private String base() {
    return "http://localhost:" + port + "/api/admin/users";
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM app_user_email WHERE email IN (:e1, :e2)")
        .param("e1", TEST_EMAIL)
        .param("e2", TEST_EMAIL_2)
        .update();
    jdbc.sql("DELETE FROM app_user WHERE display_name LIKE 'AdminTest%'").update();
  }

  // ── List ──

  @Test
  void listUsersReturnsArray() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base())).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    List<Map<String, Object>> users =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(users).isNotNull();
  }

  @Test
  void listUsersIncludesEmails() throws Exception {
    // Create a user with email first
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest ListEmail",
                "role", "user",
                "emails", List.of(TEST_EMAIL)));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    http.send(createReq, HttpResponse.BodyHandlers.ofString());

    var req = HttpRequest.newBuilder(URI.create(base())).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    List<Map<String, Object>> users =
        mapper.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
    var testUser =
        users.stream().filter(u -> "AdminTest ListEmail".equals(u.get("display_name"))).findFirst();
    assertThat(testUser).isPresent();
    assertThat(testUser.get().get("emails")).isInstanceOf(List.class);
  }

  // ── Create ──

  @Test
  void createUserReturns201() throws Exception {
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest Create",
                "role", "user",
                "emails", List.of(TEST_EMAIL)));
    var req =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("AdminTest Create");
    assertThat(body.get("role")).isEqualTo("user");
    assertThat(body.get("id")).isNotNull();
  }

  @Test
  void createUserWithMixedCaseRoleNormalizesToLowercase() throws Exception {
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest CaseRole",
                "role", "User",
                "emails", List.of(TEST_EMAIL)));
    var req =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("role")).isEqualTo("user");
  }

  @Test
  void createUserWithUppercaseAdminWorks() throws Exception {
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest UpperAdmin",
                "role", "ADMIN",
                "emails", List.of(TEST_EMAIL)));
    var req =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("role")).isEqualTo("admin");
  }

  @Test
  void createUserWithMultipleEmails() throws Exception {
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest MultiEmail",
                "role", "user",
                "emails", List.of(TEST_EMAIL, TEST_EMAIL_2)));
    var req =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);

    // Verify both emails exist
    Long count =
        jdbc.sql("SELECT COUNT(*) FROM app_user_email WHERE email IN (:e1, :e2)")
            .param("e1", TEST_EMAIL)
            .param("e2", TEST_EMAIL_2)
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(2);
  }

  // ── Update ──

  @Test
  void updateUserChangesDisplayName() throws Exception {
    // Create first
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest BeforeUpdate",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    // Update
    String updateJson = mapper.writeValueAsString(Map.of("displayName", "AdminTest AfterUpdate"));
    var updateReq =
        HttpRequest.newBuilder(URI.create(base() + "/" + id))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(updateJson))
            .build();
    var updateResp = http.send(updateReq, HttpResponse.BodyHandlers.ofString());

    assertThat(updateResp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(updateResp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("AdminTest AfterUpdate");
  }

  @Test
  void updateUserRoleNormalizesCase() throws Exception {
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest RoleUpdate",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    String updateJson = mapper.writeValueAsString(Map.of("role", "Admin"));
    var updateReq =
        HttpRequest.newBuilder(URI.create(base() + "/" + id))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(updateJson))
            .build();
    var updateResp = http.send(updateReq, HttpResponse.BodyHandlers.ofString());

    assertThat(updateResp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(updateResp.body(), Map.class);
    assertThat(body.get("role")).isEqualTo("admin");
  }

  @Test
  void updateNonexistentUserReturns404() throws Exception {
    String json = mapper.writeValueAsString(Map.of("displayName", "Nobody"));
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/999999"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Delete ──

  @Test
  void deleteUserReturns204() throws Exception {
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest ToDelete",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    var deleteReq = HttpRequest.newBuilder(URI.create(base() + "/" + id)).DELETE().build();
    var deleteResp = http.send(deleteReq, HttpResponse.BodyHandlers.ofString());

    assertThat(deleteResp.statusCode()).isEqualTo(204);
  }

  @Test
  void deleteNonexistentUserReturns404() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/999999")).DELETE().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Email management ──

  @Test
  void addEmailReturns201() throws Exception {
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest EmailAdd",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    String emailJson = mapper.writeValueAsString(Map.of("email", TEST_EMAIL));
    var addReq =
        HttpRequest.newBuilder(URI.create(base() + "/" + id + "/emails"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(emailJson))
            .build();
    var addResp = http.send(addReq, HttpResponse.BodyHandlers.ofString());

    assertThat(addResp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(addResp.body(), Map.class);
    assertThat(body.get("email")).isEqualTo(TEST_EMAIL);
  }

  @Test
  void addEmailToNonexistentUserReturns404() throws Exception {
    String emailJson = mapper.writeValueAsString(Map.of("email", TEST_EMAIL));
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/999999/emails"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(emailJson))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void addBlankEmailReturnsBadRequest() throws Exception {
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest BlankEmail",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    String emailJson = mapper.writeValueAsString(Map.of("email", "  "));
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/" + id + "/emails"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(emailJson))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(400);
  }

  @Test
  void removeEmailReturns204() throws Exception {
    // Create user with email
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest RemoveEmail",
                "role", "user",
                "emails", List.of(TEST_EMAIL)));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int userId = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    // Find the email ID
    Long emailId =
        jdbc.sql("SELECT id FROM app_user_email WHERE user_id = :uid AND email = :email")
            .param("uid", userId)
            .param("email", TEST_EMAIL)
            .query(Long.class)
            .single();

    var deleteReq =
        HttpRequest.newBuilder(URI.create(base() + "/" + userId + "/emails/" + emailId))
            .DELETE()
            .build();
    var deleteResp = http.send(deleteReq, HttpResponse.BodyHandlers.ofString());

    assertThat(deleteResp.statusCode()).isEqualTo(204);
  }

  @Test
  void removeNonexistentEmailReturns404() throws Exception {
    String createJson =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest NoEmail",
                "role", "user"));
    var createReq =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createJson))
            .build();
    var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
    int id = ((Number) mapper.readValue(createResp.body(), Map.class).get("id")).intValue();

    var req =
        HttpRequest.newBuilder(URI.create(base() + "/" + id + "/emails/999999")).DELETE().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }
}
