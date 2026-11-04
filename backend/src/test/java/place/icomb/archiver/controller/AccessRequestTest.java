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
class AccessRequestTest {

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

  private static final String ADMIN = "access-admin@example.com";
  private static final String USER = "access-user@example.com";
  private static final String STRANGER = "Stranger.Access@Example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM access_request").update();
    jdbc.sql("DELETE FROM app_user_email WHERE email LIKE '%access%'").update();
    jdbc.sql("DELETE FROM app_user WHERE display_name LIKE 'Access%'").update();
    addUser("Access Admin", "admin", ADMIN);
    addUser("Access User", "user", USER);
  }

  private long addUser(String name, String role, String email) {
    long id =
        jdbc.sql("INSERT INTO app_user (display_name, role) VALUES (:n, :r) RETURNING id")
            .param("n", name)
            .param("r", role)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", id)
        .param("e", email)
        .update();
    return id;
  }

  private HttpResponse<String> send(String method, String path, String as, String json)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (as != null) b.header("X-Auth-Email", as);
    if (json != null) b.header("Content-Type", "application/json");
    b.method(
        method,
        json == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> json(HttpResponse<String> r) throws Exception {
    return mapper.readValue(r.body(), Map.class);
  }

  private long pendingId() throws Exception {
    var list = send("GET", "/api/admin/access-requests", ADMIN, null);
    assertThat(list.statusCode()).isEqualTo(200);
    List<Map<String, Object>> rows = mapper.readValue(list.body(), List.class);
    assertThat(rows).hasSize(1);
    return ((Number) rows.get(0).get("id")).longValue();
  }

  @Test
  void aStrangerCanAskAndSeesThePendingRequest() throws Exception {
    assertThat(json(send("GET", "/api/auth/access-request", STRANGER, null)).get("status"))
        .isEqualTo("none");

    var submit =
        send(
            "POST",
            "/api/auth/access-request",
            STRANGER,
            "{\"name\":\"Stefan Huber\",\"message\":\"Counsel for the applicant\"}");
    assertThat(submit.statusCode()).isEqualTo(201);

    var mine = json(send("GET", "/api/auth/access-request", STRANGER, null));
    assertThat(mine.get("status")).isEqualTo("pending");
    assertThat(mine.get("email")).isEqualTo("stranger.access@example.com");
    assertThat(mine.get("name")).isEqualTo("Stefan Huber");
  }

  @Test
  void resubmittingAPendingRequestUpdatesItRatherThanAddingAnother() throws Exception {
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"A\",\"message\":\"one\"}");
    var again =
        send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"B\",\"message\":\"two\"}");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(jdbc.sql("SELECT count(*) FROM access_request").query(Long.class).single())
        .isEqualTo(1);
    assertThat(jdbc.sql("SELECT name FROM access_request").query(String.class).single())
        .isEqualTo("B");
  }

  @Test
  void requiresASignedInAddressAndAName() throws Exception {
    assertThat(send("POST", "/api/auth/access-request", null, "{\"name\":\"X\"}").statusCode())
        .isEqualTo(401);
    assertThat(send("GET", "/api/auth/access-request", null, null).statusCode()).isEqualTo(401);
    assertThat(send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\" \"}").statusCode())
        .isEqualTo(400);
    String longMessage = "x".repeat(2001);
    assertThat(
            send(
                    "POST",
                    "/api/auth/access-request",
                    STRANGER,
                    "{\"name\":\"X\",\"message\":\"" + longMessage + "\"}")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void anAllowlistedUserCannotFileARequest() throws Exception {
    assertThat(send("POST", "/api/auth/access-request", USER, "{\"name\":\"X\"}").statusCode())
        .isEqualTo(401);
  }

  @Test
  void approvingCreatesAUserAndTheAddressThenHasAccess() throws Exception {
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"Access Stefan\"}");
    long id = pendingId();

    var approve =
        send(
            "POST",
            "/api/admin/access-requests/" + id + "/approve",
            ADMIN,
            "{\"displayName\":\"Access Stefan Huber\",\"role\":\"user\"}");
    assertThat(approve.statusCode()).isEqualTo(200);

    var me = json(send("GET", "/api/auth/me", STRANGER, null));
    assertThat(me.get("authenticated")).isEqualTo(true);
    assertThat(me.get("displayName")).isEqualTo("Access Stefan Huber");
    assertThat(me.get("role")).isEqualTo("user");

    assertThat(
            jdbc.sql("SELECT decided_by FROM access_request WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single())
        .isEqualTo(ADMIN);
  }

  @Test
  void approvingCanAddTheAddressToAnExistingUser() throws Exception {
    long existing = addUser("Access Existing", "user", "access-existing@example.com");
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"Second address\"}");
    long id = pendingId();

    send(
        "POST",
        "/api/admin/access-requests/" + id + "/approve",
        ADMIN,
        "{\"userId\":" + existing + "}");

    assertThat(
            jdbc.sql("SELECT user_id FROM app_user_email WHERE email = :e")
                .param("e", STRANGER.toLowerCase())
                .query(Long.class)
                .single())
        .isEqualTo(existing);
  }

  @Test
  void aDeniedAddressCannotAskAgainUntilTheRequestIsDeleted() throws Exception {
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"X\"}");
    long id = pendingId();

    assertThat(send("POST", "/api/admin/access-requests/" + id + "/deny", ADMIN, null).statusCode())
        .isEqualTo(200);
    assertThat(json(send("GET", "/api/auth/access-request", STRANGER, null)).get("status"))
        .isEqualTo("denied");
    assertThat(send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"X\"}").statusCode())
        .isEqualTo(409);
    assertThat(json(send("GET", "/api/auth/me", STRANGER, null)).get("authenticated"))
        .isEqualTo(false);

    assertThat(send("DELETE", "/api/admin/access-requests/" + id, ADMIN, null).statusCode())
        .isEqualTo(204);
    assertThat(send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"X\"}").statusCode())
        .isEqualTo(201);
  }

  @Test
  void aDecidedRequestCannotBeDecidedAgain() throws Exception {
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"X\"}");
    long id = pendingId();
    send("POST", "/api/admin/access-requests/" + id + "/deny", ADMIN, null);

    assertThat(
            send("POST", "/api/admin/access-requests/" + id + "/approve", ADMIN, "{}").statusCode())
        .isEqualTo(409);
  }

  @Test
  void onlyAnAdminCanListOrDecide() throws Exception {
    send("POST", "/api/auth/access-request", STRANGER, "{\"name\":\"X\"}");
    assertThat(send("GET", "/api/admin/access-requests", USER, null).statusCode()).isEqualTo(403);
    assertThat(send("GET", "/api/admin/access-requests", STRANGER, null).statusCode())
        .isIn(401, 403);
    long id = pendingId();
    assertThat(
            send("POST", "/api/admin/access-requests/" + id + "/approve", USER, "{}").statusCode())
        .isEqualTo(403);
  }
}
