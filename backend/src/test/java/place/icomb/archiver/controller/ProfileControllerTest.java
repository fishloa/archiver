package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
class ProfileControllerTest {

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

  private static final String TEST_EMAIL = "test-profile@example.com";
  private static final String EXTRA_EMAIL = "test-profile-extra@example.com";

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
    // Clean slate
    jdbc.sql("DELETE FROM app_user_email WHERE email IN (:e1, :e2)")
        .param("e1", TEST_EMAIL)
        .param("e2", EXTRA_EMAIL)
        .update();
    jdbc.sql("DELETE FROM app_user WHERE id NOT IN (SELECT DISTINCT user_id FROM app_user_email)")
        .update();

    // Create fresh user + email
    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('Test User', 'user')"
                    + " RETURNING id")
            .query(Long.class)
            .single();

    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:userId, :email)")
        .param("userId", userId)
        .param("email", TEST_EMAIL)
        .update();
  }

  @Test
  void getProfileReturnsFamilyTreePersonId() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();

    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body).containsKey("familyTreePersonId");
    assertThat(body.get("familyTreePersonId")).isNull();
  }

  @Test
  void putProfileSetsFamilyTreePersonId() throws Exception {
    // Set familyTreePersonId — person ID 1 exists in the family tree
    var putReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 1}"))
            .build();

    var putResp = http.send(putReq, HttpResponse.BodyHandlers.ofString());
    assertThat(putResp.statusCode()).isEqualTo(200);

    var putBody = mapper.readValue(putResp.body(), Map.class);
    assertThat(putBody.get("familyTreePersonId")).isEqualTo(1);

    // Verify it persists via GET
    var getReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();

    var getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
    assertThat(getResp.statusCode()).isEqualTo(200);

    var getBody = mapper.readValue(getResp.body(), Map.class);
    assertThat(getBody.get("familyTreePersonId")).isEqualTo(1);
  }

  @Test
  void putProfileClearsFamilyTreePersonId() throws Exception {
    // First set it
    var setReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 1}"))
            .build();
    http.send(setReq, HttpResponse.BodyHandlers.ofString());

    // Then clear it with null
    var clearReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": null}"))
            .build();

    var resp = http.send(clearReq, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("familyTreePersonId")).isNull();
  }

  @Test
  void putProfileRejectsInvalidPersonId() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 999999}"))
            .build();

    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(400);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("error")).isEqualTo("Person not found");
  }

  @Test
  void authMeReturnsFamilyTreePersonId() throws Exception {
    // Set familyTreePersonId first
    var setReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 1}"))
            .build();
    http.send(setReq, HttpResponse.BodyHandlers.ofString());

    // Check /auth/me returns it
    var meReq =
        HttpRequest.newBuilder(URI.create(base() + "/auth/me"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();

    var resp = http.send(meReq, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("authenticated")).isEqualTo(true);
    assertThat(body.get("familyTreePersonId")).isEqualTo(1);
  }

  @Test
  void putProfileWithoutAuthReturns403() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"familyTreePersonId\": 1}"))
            .build();

    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(403);
  }

  @Test
  void getProfileWithoutAuthReturns401() throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base() + "/profile")).GET().build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    // No X-Auth-Email header → SecurityContext has no AppUser → 401
    assertThat(resp.statusCode()).isIn(401, 403);
  }

  // ── Display name ──

  @Test
  void getProfileReturnsDisplayName() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("Test User");
    assertThat(body.get("loginEmail")).isEqualTo(TEST_EMAIL);
    assertThat(body.get("role")).isEqualTo("user");
  }

  @Test
  void putProfileUpdatesDisplayName() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"displayName\": \"New Name\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("displayName")).isEqualTo("New Name");

    // Verify persists on GET
    var getReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
    var getBody = mapper.readValue(getResp.body(), Map.class);
    assertThat(getBody.get("displayName")).isEqualTo("New Name");
  }

  // ── Language ──

  @Test
  void putProfileUpdatesLang() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"lang\": \"de\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("lang")).isEqualTo("de");
  }

  @Test
  void putProfileIgnoresInvalidLang() throws Exception {
    // Set to "de" first
    var setReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"lang\": \"de\"}"))
            .build();
    http.send(setReq, HttpResponse.BodyHandlers.ofString());

    // Try invalid lang — should be ignored, stays "de"
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"lang\": \"xx\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("lang")).isEqualTo("de");
  }

  // ── Email CRUD ──

  @Test
  void getProfileListsEmails() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    var body = mapper.readValue(resp.body(), Map.class);
    @SuppressWarnings("unchecked")
    var emails = (List<Map<String, Object>>) body.get("emails");
    assertThat(emails).hasSize(1);
    assertThat(emails.getFirst().get("email")).isEqualTo(TEST_EMAIL);
    assertThat(emails.getFirst()).containsKey("id");
  }

  @Test
  void addEmailCreatesNewEntry() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"" + EXTRA_EMAIL + "\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);

    var body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("email")).isEqualTo(EXTRA_EMAIL);
    assertThat(body).containsKey("id");

    // Verify it appears in profile
    var getReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
    var getBody = mapper.readValue(getResp.body(), Map.class);
    @SuppressWarnings("unchecked")
    var emails = (List<Map<String, Object>>) getBody.get("emails");
    assertThat(emails).hasSize(2);
  }

  @Test
  void addEmailRejectsBlank() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(400);
  }

  @Test
  void removeExtraEmailSucceeds() throws Exception {
    // Add extra email
    var addReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails"))
            .header("X-Auth-Email", TEST_EMAIL)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"" + EXTRA_EMAIL + "\"}"))
            .build();
    var addResp = http.send(addReq, HttpResponse.BodyHandlers.ofString());
    var addBody = mapper.readValue(addResp.body(), Map.class);
    int emailId = ((Number) addBody.get("id")).intValue();

    // Remove it
    var delReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails/" + emailId))
            .header("X-Auth-Email", TEST_EMAIL)
            .DELETE()
            .build();
    var delResp = http.send(delReq, HttpResponse.BodyHandlers.ofString());
    assertThat(delResp.statusCode()).isEqualTo(204);

    // Verify gone
    var getReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
    var getBody = mapper.readValue(getResp.body(), Map.class);
    @SuppressWarnings("unchecked")
    var emails = (List<Map<String, Object>>) getBody.get("emails");
    assertThat(emails).hasSize(1);
  }

  @Test
  void cannotRemoveLoginEmail() throws Exception {
    // Get the login email's ID
    var getReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile"))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    var getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
    var getBody = mapper.readValue(getResp.body(), Map.class);
    @SuppressWarnings("unchecked")
    var emails = (List<Map<String, Object>>) getBody.get("emails");
    int loginEmailId = ((Number) emails.getFirst().get("id")).intValue();

    // Try to remove it — should fail
    var delReq =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails/" + loginEmailId))
            .header("X-Auth-Email", TEST_EMAIL)
            .DELETE()
            .build();
    var delResp = http.send(delReq, HttpResponse.BodyHandlers.ofString());
    assertThat(delResp.statusCode()).isEqualTo(400);

    var body = mapper.readValue(delResp.body(), Map.class);
    assertThat(body.get("error")).isEqualTo("Cannot remove the email you are logged in with");
  }

  @Test
  void removeNonexistentEmailReturns404() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails/999999"))
            .header("X-Auth-Email", TEST_EMAIL)
            .DELETE()
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void addEmailWithoutAuthReturns401() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(base() + "/profile/emails"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"x@example.com\"}"))
            .build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isIn(401, 403);
  }
}
