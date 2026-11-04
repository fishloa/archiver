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
import place.icomb.archiver.service.ActivityService;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;
  @Autowired private ActivityService activity;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private static final String ADMIN = "activity-admin@example.com";
  private static final String USER = "activity-user@example.com";
  private static final String STRANGER = "activity-stranger@example.com";

  private long adminId;
  private long userId;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM user_activity").update();
    jdbc.sql("DELETE FROM app_user_email WHERE email LIKE 'activity-%'").update();
    jdbc.sql("DELETE FROM app_user WHERE display_name LIKE 'Activity%'").update();
    adminId = addUser("Activity Admin", "admin", ADMIN);
    userId = addUser("Activity User", "user", USER);
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

  private HttpResponse<String> send(String method, String path, String as, String bearer)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (as != null) b.header("X-Auth-Email", as);
    if (bearer != null) b.header("Authorization", "Bearer " + bearer);
    if (!"GET".equals(method)) b.header("Content-Type", "application/json");
    b.method(
        method,
        "GET".equals(method)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString("{}"));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private int count(String where) {
    return jdbc.sql("SELECT count(*) FROM user_activity WHERE " + where)
        .query(Integer.class)
        .single();
  }

  private void awaitCount(String where, int expected) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (count(where) >= expected) return;
      Thread.sleep(50);
    }
    assertThat(count(where)).isGreaterThanOrEqualTo(expected);
  }

  private void awaitSentinel() throws Exception {
    int before = count("kind = 'view_record' AND target = '424242'");
    send("GET", "/api/records/424242", USER, null);
    awaitCount("kind = 'view_record' AND target = '424242'", before + 1);
  }

  private void insert(long uid, String at, String kind) {
    jdbc.sql(
            "INSERT INTO user_activity (user_id, email, at, method, path, status, kind)"
                + " VALUES (:u, 'e', CAST(:at AS timestamptz), 'GET', '/x', 200, :k)")
        .param("u", uid)
        .param("at", at)
        .param("k", kind)
        .update();
  }

  @Test
  void aRecordViewIsRecorded() throws Exception {
    send("GET", "/api/records/777", USER, null);
    awaitCount("kind = 'view_record' AND target = '777'", 1);
    var row = jdbc.sql("SELECT * FROM user_activity WHERE target = '777'").query().singleRow();
    assertThat(row.get("user_id")).isEqualTo(userId);
    assertThat(row.get("email")).isEqualTo(USER);
    assertThat(row.get("method")).isEqualTo("GET");
    assertThat(row.get("path")).isEqualTo("/api/records/777");
  }

  @Test
  void aSearchIsRecordedWithItsQuery() throws Exception {
    send("GET", "/api/search?q=Heydrich", USER, null);
    awaitCount("kind = 'search' AND target = 'Heydrich'", 1);
  }

  @Test
  void aPostIsRecordedAsAnEdit() throws Exception {
    send("POST", "/api/profile/emails", USER, null);
    awaitCount("kind = 'edit' AND method = 'POST'", 1);
  }

  @Test
  void noiseIsNotRecorded() throws Exception {
    send("GET", "/api/files/1", USER, null);
    send("GET", "/api/files/1/thumbnail", USER, null);
    send("GET", "/api/auth/me", USER, null);
    send("GET", "/api/version", USER, null);
    send("GET", "/api/pipeline/stats", USER, null);
    send("GET", "/api/pdf-exports/1", USER, null);
    send("GET", "/api/family-tree/media/1/image", USER, null);

    var sse =
        http.sendAsync(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/records/events"))
                .header("X-Auth-Email", USER)
                .header("Accept", "text/event-stream")
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    sse.get().body().close();

    awaitSentinel();
    assertThat(count("target IS DISTINCT FROM '424242'")).isZero();
  }

  @Test
  void bearerAndNotAllowlistedCallersAreNotRecorded() throws Exception {
    send("GET", "/api/records/1", null, "test-processor-token");
    send("GET", "/api/records/2", STRANGER, null);
    send("GET", "/api/records/3", null, null);
    send("GET", "/api/records/4", USER, "test-processor-token");

    awaitSentinel();
    assertThat(count("target IN ('1','2','3')")).isZero();
  }

  @Test
  void sessionsSplitOnAGapOverThirtyMinutes() throws Exception {
    insert(userId, "2026-01-01T10:00:00Z", "view_record");
    insert(userId, "2026-01-01T10:20:00Z", "view_page");
    insert(userId, "2026-01-01T10:50:00Z", "edit");
    insert(userId, "2026-01-01T11:20:01Z", "view_record");
    insert(userId, "2026-01-01T11:30:00Z", "edit");
    insert(adminId, "2026-01-01T10:05:00Z", "view_person");

    var res = send("GET", "/api/admin/activity/sessions?userId=" + userId, ADMIN, null);
    assertThat(res.statusCode()).isEqualTo(200);
    List<Map<String, Object>> sessions = mapper.readValue(res.body(), List.class);
    assertThat(sessions).hasSize(2);

    var newest = sessions.get(0);
    assertThat(((Number) newest.get("event_count")).intValue()).isEqualTo(2);
    assertThat(((Number) newest.get("view_count")).intValue()).isEqualTo(1);
    assertThat(((Number) newest.get("edit_count")).intValue()).isEqualTo(1);
    assertThat(((Number) newest.get("duration_seconds")).intValue()).isEqualTo(599);
    assertThat(newest.get("display_name")).isEqualTo("Activity User");

    var oldest = sessions.get(1);
    assertThat(((Number) oldest.get("event_count")).intValue()).isEqualTo(3);
    assertThat(((Number) oldest.get("duration_seconds")).intValue()).isEqualTo(3000);

    var all =
        mapper.readValue(
            send("GET", "/api/admin/activity/sessions", ADMIN, null).body(), List.class);
    assertThat(all).hasSize(3);

    var events =
        mapper.readValue(
            send("GET", "/api/admin/activity/events?userId=" + userId + "&limit=2", ADMIN, null)
                .body(),
            List.class);
    assertThat(events).hasSize(2);
  }

  @Test
  void adminEndpointsRefuseNonAdmins() throws Exception {
    for (String path : List.of("/api/admin/activity/sessions", "/api/admin/activity/events")) {
      assertThat(send("GET", path, USER, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", path, STRANGER, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", path, ADMIN, null).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void retentionDeletesRowsOlderThanTwoYears() {
    jdbc.sql(
            "INSERT INTO user_activity (user_id, email, at, method, path, status, kind)"
                + " SELECT :u, 'e', now() - make_interval(days => d), 'GET', '/x', 200, 'other'"
                + " FROM unnest(ARRAY[731, 760, 700, 10]) AS d")
        .param("u", userId)
        .update();
    int deleted = activity.purge();
    assertThat(deleted).isEqualTo(2);
    assertThat(count("true")).isEqualTo(2);
  }
}
