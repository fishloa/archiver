package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

/** Hand corrections to a stored transcription, translation or title, and the audit they leave. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManualCorrectionTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;

  private String base;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN_EMAIL = "correction-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private long record;
  private long page;

  @BeforeEach
  void setUp() throws Exception {
    base = "http://localhost:" + port + "/api";
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM record").update();
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'CorrectionAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('CorrectionAdmin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();

    long archive =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:n, 'AT') RETURNING id")
            .param("n", "Correction Archive " + UUID.randomUUID())
            .query(Long.class)
            .single();
    record = newRecord(archive);
    page = uploadPage(record, 1);
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, confidence, text_raw, text_en)"
                + " VALUES (:p, 'transkribus', 0.9, :t, :e)")
        .param("p", page)
        .param("t", "Er ist am 17. Jänner 1958 gestorben.\nSchönhof, am 17. Jänner 1938.")
        .param("e", "He died on 17 January 1958.\nSchönhof, 17 January 1938.")
        .update();
    jdbc.sql("INSERT INTO page_translation (page_id, model, text_en) VALUES (:p, 'm', :e)")
        .param("p", page)
        .param("e", "He died on 17 January 1958.\nSchönhof, 17 January 1938.")
        .update();
    jdbc.sql(
            "INSERT INTO text_chunk (record_id, page_id, chunk_index, content)"
                + " VALUES (:r, :p, 0, :t)")
        .param("r", record)
        .param("p", page)
        .param("t", "Er ist am 17. Jänner 1958 gestorben.")
        .update();
    jdbc.sql(
            "UPDATE record SET title_en = 'Party ticket for Paul', description_en ="
                + " 'Party ticket (obituary notice)' WHERE id = :r")
        .param("r", record)
        .update();
  }

  @Test
  void aTextCorrectionChangesOnlyThatPassageAndTheChunkFollows() throws Exception {
    JsonNode out =
        ok(
            post(
                "/admin/pages/" + page + "/corrections",
                body("text_raw", "am 17. Jänner 1958", "am 17. Jänner 1938", "year misread")));
    assertThat(out.get("applied").asBoolean()).isTrue();

    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", page))
        .isEqualTo("Er ist am 17. Jänner 1938 gestorben.\nSchönhof, am 17. Jänner 1938.");
    assertThat(text("SELECT content FROM text_chunk WHERE page_id = ?", page))
        .isEqualTo("Er ist am 17. Jänner 1938 gestorben.");
    // the English is a different field and is left alone
    assertThat(text("SELECT text_en FROM page_text WHERE page_id = ?", page)).contains("1958");
  }

  @Test
  void anEnglishCorrectionReachesEveryCopyOfTheTranslation() throws Exception {
    ok(
        post(
            "/admin/pages/" + page + "/corrections",
            body("text_en", "died on 17 January 1958", "died on 17 January 1938", "year")));
    assertThat(text("SELECT text_en FROM page_text WHERE page_id = ?", page))
        .isEqualTo("He died on 17 January 1938.\nSchönhof, 17 January 1938.");
    assertThat(text("SELECT text_en FROM page_translation WHERE page_id = ?", page))
        .isEqualTo("He died on 17 January 1938.\nSchönhof, 17 January 1938.");
  }

  @Test
  void aCorrectionIsAuditedWithWhatItReplacedWhoAndWhy() throws Exception {
    ok(
        post(
            "/admin/pages/" + page + "/corrections",
            body("text_raw", "1958", "1938", "the dateline says 1938")));
    JsonNode rows = json.readTree(get("/admin/records/" + record + "/corrections").body());
    assertThat(rows).hasSize(1);
    JsonNode row = rows.get(0);
    assertThat(row.get("oldText").asText()).isEqualTo("1958");
    assertThat(row.get("newText").asText()).isEqualTo("1938");
    assertThat(row.get("reason").asText()).isEqualTo("the dateline says 1938");
    assertThat(row.get("field").asText()).isEqualTo("text_raw");
    assertThat(row.get("pageId").asLong()).isEqualTo(page);
    assertThat(row.get("correctedBy").asText()).isNotBlank();
  }

  @Test
  void textThatOccursTwiceIsRefusedAndNothingChanges() throws Exception {
    // "17. Jänner" appears twice
    HttpResponse<String> resp =
        post(
            "/admin/pages/" + page + "/corrections",
            body("text_raw", "17. Jänner", "18. Jänner", "ambiguous"));
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(409);
    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", page))
        .contains("1958")
        .doesNotContain("18. Jänner");
    assertThat(count("SELECT count(*) FROM manual_correction")).isZero();
  }

  @Test
  void textThatIsNotThereIsRefused() throws Exception {
    HttpResponse<String> resp =
        post("/admin/pages/" + page + "/corrections", body("text_raw", "1999", "2000", "absent"));
    assertThat(resp.statusCode()).isEqualTo(409);
    assertThat(count("SELECT count(*) FROM manual_correction")).isZero();
  }

  @Test
  void badRequestsAreFourHundredsNotFiveHundreds() throws Exception {
    String p = "/admin/pages/" + page + "/corrections";
    assertThat(post(p, body("text_raw", "1958", "1938", "  ")).statusCode()).isEqualTo(400);
    assertThat(post(p, body("text_raw", "", "x", "r")).statusCode()).isEqualTo(400);
    assertThat(post(p, body("text_raw", "1958", "1958", "r")).statusCode()).isEqualTo(400);
    assertThat(post(p, body("page_count", "1", "2", "r")).statusCode()).isEqualTo(400);
    assertThat(
            post(p, "{\"field\":\"text_raw\",\"find\":5,\"replace\":\"x\",\"reason\":\"r\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(post(p, "{}").statusCode()).isEqualTo(400);
    assertThat(
            post("/admin/pages/99999999/corrections", body("text_raw", "a", "b", "r")).statusCode())
        .isEqualTo(404);
    assertThat(count("SELECT count(*) FROM manual_correction")).isZero();
  }

  @Test
  void aWholePageTextCanBeReplacedWhenAskedForExplicitly() throws Exception {
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "field",
                "text_raw",
                "replace",
                "Eine Zeile.\nZweite Zeile.",
                "reason",
                "by hand",
                "replaceAll",
                true));
    JsonNode out = ok(post("/admin/pages/" + page + "/corrections", body));
    assertThat(out.get("whole").asBoolean()).isTrue();
    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", page))
        .isEqualTo("Eine Zeile.\nZweite Zeile.");
    // the old chunks no longer describe the page; they go, and re-embedding rebuilds them
    assertThat(count("SELECT count(*) FROM text_chunk WHERE page_id = ?", page)).isZero();
    JsonNode audit = json.readTree(get("/admin/records/" + record + "/corrections").body()).get(0);
    assertThat(audit.get("oldText").asText()).contains("1958");
    assertThat(audit.get("newText").asText()).isEqualTo("Eine Zeile.\nZweite Zeile.");

    String en =
        json.writeValueAsString(
            java.util.Map.of(
                "field",
                "text_en",
                "replace",
                "One line.",
                "reason",
                "by hand",
                "replaceAll",
                true));
    ok(post("/admin/pages/" + page + "/corrections", en));
    assertThat(text("SELECT text_en FROM page_text WHERE page_id = ?", page))
        .isEqualTo("One line.");
    assertThat(text("SELECT text_en FROM page_translation WHERE page_id = ?", page))
        .isEqualTo("One line.");
  }

  @Test
  void aMissingFindIsNeverTakenAsAWholeReplacement() throws Exception {
    String p = "/admin/pages/" + page + "/corrections";
    assertThat(post(p, "{\"field\":\"text_raw\",\"replace\":\"x\",\"reason\":\"r\"}").statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    p,
                    "{\"field\":\"text_raw\",\"find\":\"1958\",\"replace\":\"x\",\"reason\":\"r\",\"replaceAll\":true}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    p,
                    "{\"field\":\"text_raw\",\"replace\":\"x\",\"reason\":\"r\",\"replaceAll\":\"yes\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", page)).contains("1958");
  }

  @Test
  void aRecordTitleAndDescriptionCanBeCorrected() throws Exception {
    String p = "/admin/records/" + record + "/corrections";
    ok(post(p, body("title_en", "Party ticket", "Death notice", "Partezettel is a death notice")));
    ok(
        post(
            p,
            body(
                "description_en",
                "Party ticket (obituary notice)",
                "Death notice (Partezettel)",
                "same")));
    assertThat(text("SELECT title_en FROM record WHERE id = ?", record))
        .isEqualTo("Death notice for Paul");
    assertThat(text("SELECT description_en FROM record WHERE id = ?", record))
        .isEqualTo("Death notice (Partezettel)");
    assertThat(count("SELECT count(*) FROM manual_correction WHERE record_id = ?", record))
        .isEqualTo(2);
    assertThat(
            post("/admin/records/99999999/corrections", body("title", "a", "b", "r")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void aCorrectionNeedsAnAdministrator() throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/pages/" + page + "/corrections"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body("text_raw", "1958", "1938", "r")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isIn(401, 403);
    assertThat(text("SELECT text_raw FROM page_text WHERE page_id = ?", page)).contains("1958");
  }

  // --- harness ----------------------------------------------------------------------------

  private String body(String field, String find, String replace, String reason) throws Exception {
    return json.writeValueAsString(
        java.util.Map.of("field", field, "find", find, "replace", replace, "reason", reason));
  }

  private long newRecord(long archiveId) throws Exception {
    String body =
        """
        {"archiveId":%d,"sourceSystem":"test","sourceRecordId":"%s","lang":"de","metadataLang":"de"}
        """
            .formatted(archiveId, UUID.randomUUID());
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    return json.readTree(resp.body()).get("id").asLong();
  }

  private long uploadPage(long recordId, int seq) throws Exception {
    String boundary = "----B" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"p.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .formatted(boundary)
            .getBytes(StandardCharsets.UTF_8));
    out.write(("page-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/pages?seq=" + seq))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    return json.readTree(resp.body()).get("id").asLong();
  }

  private HttpResponse<String> post(String path, String jsonBody) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(HttpResponse<String> resp) throws Exception {
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    return json.readTree(resp.body());
  }

  private String text(String sql, Object id) {
    return jdbc.sql(sql).param(id).query(String.class).single();
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(java.util.Arrays.asList(params)).query(Long.class).single();
  }
}
