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

  private static final String ADMIN_EMAIL = "admin-test-admin@example.com";
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
    // Clean test emails
    jdbc.sql("DELETE FROM app_user_email WHERE email IN (:e1, :e2, :e3)")
        .param("e1", ADMIN_EMAIL)
        .param("e2", TEST_EMAIL)
        .param("e3", TEST_EMAIL_2)
        .update();
    jdbc.sql("DELETE FROM app_user WHERE display_name LIKE 'AdminTest%'").update();

    // Create an admin user for auth
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('AdminTest Admin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :email)")
        .param("uid", adminId)
        .param("email", ADMIN_EMAIL)
        .update();
  }

  /** Helper: build a POST request authenticated as admin. */
  private HttpRequest.Builder adminPost(String url, String json) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("X-Auth-Email", ADMIN_EMAIL)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json));
  }

  /** Helper: build a PUT request authenticated as admin. */
  private HttpRequest.Builder adminPut(String url, String json) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("X-Auth-Email", ADMIN_EMAIL)
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(json));
  }

  /** Helper: build a DELETE request authenticated as admin. */
  private HttpRequest.Builder adminDelete(String url) {
    return HttpRequest.newBuilder(URI.create(url)).header("X-Auth-Email", ADMIN_EMAIL).DELETE();
  }

  /** Helper: create a test user via the API, return the id. */
  private int createTestUser(String name, String role, List<String> emails) throws Exception {
    var body =
        emails != null
            ? Map.of("displayName", name, "role", role, "emails", emails)
            : Map.of("displayName", name, "role", role);
    String json = mapper.writeValueAsString(body);
    var resp = http.send(adminPost(base(), json).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    return ((Number) mapper.readValue(resp.body(), Map.class).get("id")).intValue();
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
    createTestUser("AdminTest ListEmail", "user", List.of(TEST_EMAIL));

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
    var resp = http.send(adminPost(base(), json).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("AdminTest Create");
    assertThat(body.get("role")).isEqualTo("user");
    assertThat(body.get("id")).isNotNull();
  }

  @Test
  void createUserWithoutAuthReturns403() throws Exception {
    String json =
        mapper.writeValueAsString(Map.of("displayName", "AdminTest NoAuth", "role", "user"));
    var req =
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(403);
  }

  @Test
  void createUserWithMixedCaseRoleNormalizesToLowercase() throws Exception {
    String json =
        mapper.writeValueAsString(
            Map.of(
                "displayName", "AdminTest CaseRole",
                "role", "User",
                "emails", List.of(TEST_EMAIL)));
    var resp = http.send(adminPost(base(), json).build(), HttpResponse.BodyHandlers.ofString());

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
    var resp = http.send(adminPost(base(), json).build(), HttpResponse.BodyHandlers.ofString());

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
    var resp = http.send(adminPost(base(), json).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);

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
    int id = createTestUser("AdminTest BeforeUpdate", "user", null);

    String updateJson = mapper.writeValueAsString(Map.of("displayName", "AdminTest AfterUpdate"));
    var resp =
        http.send(
            adminPut(base() + "/" + id, updateJson).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("AdminTest AfterUpdate");
  }

  @Test
  void updateUserRoleNormalizesCase() throws Exception {
    int id = createTestUser("AdminTest RoleUpdate", "user", null);

    String updateJson = mapper.writeValueAsString(Map.of("role", "Admin"));
    var resp =
        http.send(
            adminPut(base() + "/" + id, updateJson).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("role")).isEqualTo("admin");
  }

  @Test
  void updateNonexistentUserReturns404() throws Exception {
    String json = mapper.writeValueAsString(Map.of("displayName", "Nobody"));
    var resp =
        http.send(adminPut(base() + "/999999", json).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Delete ──

  @Test
  void deleteUserReturns204() throws Exception {
    int id = createTestUser("AdminTest ToDelete", "user", null);

    var resp =
        http.send(adminDelete(base() + "/" + id).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(204);
  }

  @Test
  void deleteNonexistentUserReturns404() throws Exception {
    var resp =
        http.send(adminDelete(base() + "/999999").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Email management ──

  @Test
  void addEmailReturns201() throws Exception {
    int id = createTestUser("AdminTest EmailAdd", "user", null);

    String emailJson = mapper.writeValueAsString(Map.of("email", TEST_EMAIL));
    var resp =
        http.send(
            adminPost(base() + "/" + id + "/emails", emailJson).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(201);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("email")).isEqualTo(TEST_EMAIL);
  }

  @Test
  void addEmailToNonexistentUserReturns404() throws Exception {
    String emailJson = mapper.writeValueAsString(Map.of("email", TEST_EMAIL));
    var resp =
        http.send(
            adminPost(base() + "/999999/emails", emailJson).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void addBlankEmailReturnsBadRequest() throws Exception {
    int id = createTestUser("AdminTest BlankEmail", "user", null);

    String emailJson = mapper.writeValueAsString(Map.of("email", "  "));
    var resp =
        http.send(
            adminPost(base() + "/" + id + "/emails", emailJson).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(400);
  }

  @Test
  void removeEmailReturns204() throws Exception {
    int userId = createTestUser("AdminTest RemoveEmail", "user", List.of(TEST_EMAIL));

    Long emailId =
        jdbc.sql("SELECT id FROM app_user_email WHERE user_id = :uid AND email = :email")
            .param("uid", userId)
            .param("email", TEST_EMAIL)
            .query(Long.class)
            .single();

    var resp =
        http.send(
            adminDelete(base() + "/" + userId + "/emails/" + emailId).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(204);
  }

  @Test
  void removeNonexistentEmailReturns404() throws Exception {
    int id = createTestUser("AdminTest NoEmail", "user", null);

    var resp =
        http.send(
            adminDelete(base() + "/" + id + "/emails/999999").build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }
}
