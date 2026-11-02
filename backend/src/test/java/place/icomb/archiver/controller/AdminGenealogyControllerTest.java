package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.JsonNode;
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
import place.icomb.archiver.genealogy.GenealogyFixtures;

/** The genealogy admin API over HTTP: who may call it, and the statuses it answers with. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminGenealogyControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired private place.icomb.archiver.service.StorageService storage;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN = "genealogy-admin@example.com";
  private static final String USER = "genealogy-reader@example.com";

  @BeforeEach
  void users() {
    for (String[] u : new String[][] {{ADMIN, "admin", "GenAdmin"}, {USER, "user", "GenReader"}}) {
      jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", u[0]).update();
      jdbc.sql("DELETE FROM app_user WHERE display_name = :n").param("n", u[2]).update();
      Long id =
          jdbc.sql("INSERT INTO app_user (display_name, role) VALUES (:n, :r) RETURNING id")
              .param("n", u[2])
              .param("r", u[1])
              .query(Long.class)
              .single();
      jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
          .param("id", id)
          .param("e", u[0])
          .update();
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + "/api" + path;
  }

  private HttpResponse<String> send(String method, String path, String body, String... headers)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> admin(String method, String path, Object body) throws Exception {
    return send(
        method, path, body == null ? null : json.writeValueAsString(body), "X-Auth-Email", ADMIN);
  }

  private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    return json.readTree(r.body());
  }

  @Test
  void anAdministratorCreatesEditsAndDeletesAPerson() throws Exception {
    JsonNode created =
        ok(
            admin(
                "POST",
                "/admin/genealogy/persons",
                Map.of("given", "Anna", "surname", "Novák", "sex", "F", "reason", "letter")),
            201);
    long id = created.get("id").asLong();
    assertThat(id).isGreaterThan(348);

    ok(
        admin(
            "POST",
            "/admin/genealogy/persons/" + id + "/names",
            Map.of(
                "type", "married", "given", "Anna", "surname", "Svobodová", "reason", "marriage")),
        201);
    JsonNode event =
        ok(
            admin(
                "POST",
                "/admin/genealogy/events",
                Map.of(
                    "personId",
                    id,
                    "type",
                    "BIRT",
                    "dateText",
                    "12 MAR 1890",
                    "reason",
                    "register")),
            201);
    assertThat(event.get("dateFrom").asText()).isEqualTo("1890-03-12");
    ok(
        admin(
            "PUT",
            "/admin/genealogy/events/" + event.get("id").asLong(),
            Map.of("dateText", "ABT 1890", "reason", "the register is unsure")),
        200);

    JsonNode person = ok(send("GET", "/family-tree/person/" + id, null, "X-Auth-Email", USER), 200);
    assertThat(person.get("names")).hasSize(2);
    assertThat(person.get("events").get(0).get("date").asText()).isEqualTo("ABT 1890");

    JsonNode deleted =
        ok(admin("DELETE", "/admin/genealogy/persons/" + id, Map.of("reason", "test over")), 200);
    assertThat(deleted.get("deleted").asInt()).isEqualTo(4);
  }

  @Test
  void refusalsAreFourHundredsWithAnError() throws Exception {
    assertThat(admin("POST", "/admin/genealogy/persons", Map.of("given", "x")).statusCode())
        .isEqualTo(400);
    assertThat(
            admin("POST", "/admin/genealogy/persons", Map.of("given", "x", "reason", 5))
                .statusCode())
        .isEqualTo(400);
    HttpResponse<String> badDate =
        admin(
            "POST",
            "/admin/genealogy/events",
            Map.of("personId", 322, "type", "BIRT", "dateText", "30.4.1913", "reason", "x"));
    assertThat(badDate.statusCode()).isEqualTo(400);
    assertThat(json.readTree(badDate.body()).get("error").asText()).contains("30.4.1913");
    assertThat(admin("DELETE", "/admin/genealogy/persons/322", Map.of("reason", "x")).statusCode())
        .isEqualTo(409);
    assertThat(
            admin("PUT", "/admin/genealogy/persons/999999", Map.of("notes", "x", "reason", "x"))
                .statusCode())
        .isEqualTo(404);
    assertThat(admin("POST", "/admin/genealogy/widgets", Map.of("reason", "x")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void aChangeCanBeListedAndUndone() throws Exception {
    long name =
        jdbc.sql("SELECT id FROM gen_name WHERE person_id = 324 AND is_primary")
            .query(Long.class)
            .single();
    ok(
        admin(
            "PUT",
            "/admin/genealogy/names/" + name,
            Map.of("nickname", "Sandy", "reason", "family usage")),
        200);
    JsonNode changes = ok(admin("GET", "/admin/genealogy/changes?personId=324", null), 200);
    assertThat(changes.get(0).get("entity").asText()).isEqualTo("name");
    assertThat(changes.get(0).get("after").get("nickname").asText()).isEqualTo("Sandy");
    long change = changes.get(0).get("id").asLong();

    ok(
        admin("POST", "/admin/genealogy/changes/" + change + "/undo", Map.of("reason", "wrong")),
        200);
    assertThat(
            jdbc.sql("SELECT nickname FROM gen_name WHERE id = :id")
                .param("id", name)
                .query(String.class)
                .optional())
        .isEmpty();
    assertThat(
            admin("POST", "/admin/genealogy/changes/" + change + "/undo", Map.of("reason", "again"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void theReviewListShowsTheImportsQuestions() throws Exception {
    JsonNode review = ok(admin("GET", "/admin/genealogy/review", null), 200);
    assertThat(review.size()).isGreaterThan(5);
    assertThat(review.toString()).contains("conflicting birth dates");
  }

  @Test
  void mergeOverHttp() throws Exception {
    long a =
        ok(admin("POST", "/admin/genealogy/persons", Map.of("given", "A", "reason", "x")), 201)
            .get("id")
            .asLong();
    long b =
        ok(admin("POST", "/admin/genealogy/persons", Map.of("given", "B", "reason", "x")), 201)
            .get("id")
            .asLong();
    JsonNode out =
        ok(
            admin(
                "POST", "/admin/genealogy/persons/" + a + "/merge/" + b, Map.of("reason", "same")),
            200);
    assertThat(out.get("keep").asLong()).isEqualTo(a);
    assertThat(
            ok(send("GET", "/family-tree/person/" + b, null, "X-Auth-Email", USER), 200)
                .get("id")
                .asLong())
        .isEqualTo(a);
  }

  @Test
  void everyAdminEndpointRefusesUsersAndProcessors() throws Exception {
    List<String[]> calls =
        List.of(
            new String[] {"POST", "/admin/genealogy/persons"},
            new String[] {"POST", "/admin/genealogy/persons/322/names"},
            new String[] {"PUT", "/admin/genealogy/persons/322"},
            new String[] {"DELETE", "/admin/genealogy/events/1"},
            new String[] {"POST", "/admin/genealogy/persons/322/merge/323"},
            new String[] {"GET", "/admin/genealogy/changes"},
            new String[] {"POST", "/admin/genealogy/changes/1/undo"},
            new String[] {"GET", "/admin/genealogy/review"},
            new String[] {"GET", "/admin/genealogy/places"},
            new String[] {"GET", "/admin/genealogy/sources"},
            new String[] {"GET", "/admin/genealogy/export.ged"});
    String body = "{\"reason\":\"not allowed\",\"notes\":\"x\"}";
    long before = jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single();
    for (String[] c : calls) {
      assertThat(send(c[0], c[1], body, "X-Auth-Email", USER).statusCode())
          .as("user " + c[0] + " " + c[1])
          .isIn(401, 403);
      assertThat(send(c[0], c[1], body, "Authorization", PROCESSOR_AUTH_HEADER).statusCode())
          .as("processor " + c[0] + " " + c[1])
          .isIn(401, 403);
      assertThat(send(c[0], c[1], body).statusCode())
          .as("anonymous " + c[0] + " " + c[1])
          .isIn(401, 403);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single())
        .isEqualTo(before);
  }

  @Test
  void anImageCanBeMadeSomeonesPortraitInOneWrite() throws Exception {
    long record = GenealogyFixtures.record(jdbcTemplate);
    long page =
        GenealogyFixtures.page(
            jdbcTemplate, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    long before = jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single();
    JsonNode media =
        ok(
            admin(
                "POST",
                "/admin/genealogy/media",
                Map.of(
                    "pageId",
                    page,
                    "cropLeft",
                    0,
                    "cropTop",
                    0,
                    "cropWidth",
                    20,
                    "cropHeight",
                    20,
                    "personId",
                    322,
                    "isPortrait",
                    true,
                    "reason",
                    "wedding photograph")),
            201);
    assertThat(jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single())
        .isEqualTo(before + 2);
    JsonNode person = ok(send("GET", "/family-tree/person/322", null, "X-Auth-Email", USER), 200);
    assertThat(person.get("portrait").get("id").asLong()).isEqualTo(media.get("id").asLong());
    assertThat(person.get("portrait").get("imageUrl").asText())
        .isEqualTo("/api/family-tree/media/" + media.get("id").asLong() + "/image");
  }
}
