package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportWorker;

/**
 * Asking for a PDF, waiting for it, and downloading it — the whole flow through HTTP, as an
 * ordinary signed-in user rather than an administrator.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfExportApiTest {

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

  private static final String EMAIL = "pdf-export-user@example.com";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;
  @Autowired private PdfExportWorker worker;
  @Autowired private place.icomb.archiver.service.IngestService ingestService;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private String base;
  private long record;

  @BeforeEach
  void setUp() throws Exception {
    base = "http://localhost:" + port + "/api";
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", EMAIL);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'PdfExportUser'");
    Long user =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('PdfExportUser', 'user')"
                + " RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", user, EMAIL);

    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    for (int seq = 1; seq <= 3; seq++) {
      PdfFixtures.page(jdbc, storageRoot, record, seq, "English " + seq);
    }
  }

  private HttpResponse<String> post(String path, String body, boolean signedIn) throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (signedIn) {
      req.header("X-Auth-Email", EMAIL);
    }
    return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, boolean signedIn) throws Exception {
    HttpRequest.Builder req = HttpRequest.newBuilder().uri(URI.create(base + path)).GET();
    if (signedIn) {
      req.header("X-Auth-Email", EMAIL);
    }
    return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> getBytes(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("X-Auth-Email", EMAIL)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private JsonNode created(String body, int status) throws Exception {
    HttpResponse<String> resp = post("/records/" + record + "/pdf-exports", body, true);
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
    return json.readTree(resp.body());
  }

  private long exports() {
    return jdbc.queryForObject("SELECT count(*) FROM pdf_export", Long.class);
  }

  // --- the flow ----------------------------------------------------------------------------

  @Test
  void requestWaitThenDownloadTheWholeRecord() throws Exception {
    JsonNode made = created("{}", 202);
    String id = made.get("id").asText();
    assertThat(made.get("state").asText()).isEqualTo("queued");
    assertThat(made.get("pageCount").asInt()).isEqualTo(3);
    assertThat(made.get("variant").asText()).isEqualTo("original");

    HttpResponse<String> early = get("/pdf-exports/" + id + "/file", true);
    assertThat(early.statusCode()).as("not ready yet").isEqualTo(409);

    worker.runOnce();

    JsonNode status = json.readTree(get("/pdf-exports/" + id, true).body());
    assertThat(status.get("state").asText()).isEqualTo("ready");
    assertThat(status.get("bytes").asLong()).isPositive();
    assertThat(status.get("expiresAt").asText()).isNotBlank();

    HttpResponse<byte[]> file = getBytes("/pdf-exports/" + id + "/file");
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(file.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");
    assertThat(file.headers().firstValue("Content-Disposition").orElse(""))
        .contains("attachment")
        .contains("record-" + record + "-original.pdf");
    try (PDDocument doc = Loader.loadPDF(file.body())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(3);
    }
  }

  @Test
  void aRangeExportsOnlyThosePages() throws Exception {
    String id = created("{\"pages\":\"2-3\"}", 202).get("id").asText();
    worker.runOnce();

    try (PDDocument doc = Loader.loadPDF(getBytes("/pdf-exports/" + id + "/file").body())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
    }
  }

  @Test
  void aHugeRangeIsClampedToTheRecordsPages() throws Exception {
    assertThat(created("{\"pages\":\"1-99999999\"}", 202).get("pageCount").asInt()).isEqualTo(3);
  }

  @Test
  void aRangeEntirelyPastTheRecordIsRefused() throws Exception {
    HttpResponse<String> resp =
        post("/records/" + record + "/pdf-exports", "{\"pages\":\"90000-99999999\"}", true);
    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(exports()).isZero();
  }

  @Test
  void theEnglishAndSideBySideVariantsAreNamedForTheirContent() throws Exception {
    String english = created("{\"variant\":\"english\"}", 202).get("id").asText();
    String both = created("{\"variant\":\"side-by-side\"}", 202).get("id").asText();
    worker.drain();

    assertThat(
            getBytes("/pdf-exports/" + english + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-english.pdf");
    assertThat(
            getBytes("/pdf-exports/" + both + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-original-and-english.pdf");
  }

  // --- reuse -------------------------------------------------------------------------------

  @Test
  void anIdenticalRequestWhileOneIsQueuedJoinsIt() throws Exception {
    String first = created("{}", 202).get("id").asText();

    String second = created("{}", 202).get("id").asText();

    assertThat(second).isEqualTo(first);
    assertThat(exports()).isEqualTo(1L);
  }

  @Test
  void anIdenticalRequestAfterItIsReadyAnswersOkWithTheSameExport() throws Exception {
    String first = created("{}", 202).get("id").asText();
    worker.runOnce();

    JsonNode again = created("{}", 200);

    assertThat(again.get("id").asText()).isEqualTo(first);
    assertThat(again.get("state").asText()).isEqualTo("ready");
    assertThat(exports()).isEqualTo(1L);
  }

  @Test
  void changingAPagesTextStartsANewExportInsteadOfServingTheOldOne() throws Exception {
    String first = created("{}", 202).get("id").asText();
    worker.runOnce();
    jdbc.update(
        "UPDATE page_text SET text_en = 'Corrected' WHERE page_id = (SELECT id FROM page WHERE record_id = ? AND seq = 1)",
        record);

    String second = created("{}", 202).get("id").asText();

    assertThat(second).isNotEqualTo(first);
  }

  // --- states the file endpoint refuses ----------------------------------------------------

  @Test
  void anExpiredExportIsGoneNotMissing() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");
    worker.reap();

    assertThat(get("/pdf-exports/" + id + "/file", true).statusCode()).isEqualTo(410);
    assertThat(json.readTree(get("/pdf-exports/" + id, true).body()).get("state").asText())
        .isEqualTo("expired");
  }

  @Test
  void aFileThatHasVanishedFromDiskIsGone() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    String path = jdbc.queryForObject("SELECT path FROM pdf_export", String.class);
    Files.delete(storageRoot.resolve(path));

    assertThat(get("/pdf-exports/" + id + "/file", true).statusCode()).isEqualTo(410);
  }

  @Test
  void aFailedExportExplainsWhy() throws Exception {
    String id = created("{}", 202).get("id").asText();
    jdbc.update("UPDATE pdf_export SET state = 'failed', error = 'no scan for page 2'");

    HttpResponse<String> resp = get("/pdf-exports/" + id + "/file", true);

    assertThat(resp.statusCode()).isEqualTo(409);
    assertThat(resp.body()).contains("no scan for page 2");
  }

  @Test
  void anUnknownOrMalformedIdIsNotFound() throws Exception {
    assertThat(get("/pdf-exports/00000000-0000-0000-0000-000000000000", true).statusCode())
        .isEqualTo(404);
    assertThat(get("/pdf-exports/00000000-0000-0000-0000-000000000000/file", true).statusCode())
        .isEqualTo(404);
    assertThat(get("/pdf-exports/not-a-uuid", true).statusCode()).isEqualTo(404);
    assertThat(get("/pdf-exports/not-a-uuid/file", true).statusCode()).isEqualTo(404);
  }

  // --- refusals ----------------------------------------------------------------------------

  @Test
  void badRequestsAreRefusedAndCreateNothing() throws Exception {
    for (String body :
        List.of(
            "{\"variant\":\"pdf\"}",
            "{\"variant\":7}",
            "{\"pages\":\"a-b\"}",
            "{\"pages\":99}",
            "{\"pages\":\"99\"}")) {
      HttpResponse<String> resp = post("/records/" + record + "/pdf-exports", body, true);
      assertThat(resp.statusCode()).as(body).isEqualTo(400);
    }
    assertThat(exports()).isZero();
  }

  @Test
  void aRecordWithNoPagesHasNothingToExport() throws Exception {
    long empty = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Empty");

    HttpResponse<String> resp = post("/records/" + empty + "/pdf-exports", "{}", true);

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(exports()).isZero();
  }

  @Test
  void anUnknownRecordIsNotFound() throws Exception {
    assertThat(post("/records/99999999/pdf-exports", "{}", true).statusCode()).isEqualTo(404);
  }

  @Test
  void anyoneSignedInMayAskButAnonymousCallersAreRefused() throws Exception {
    String id = created("{}", 202).get("id").asText();

    assertThat(post("/records/" + record + "/pdf-exports", "{}", false).statusCode())
        .isIn(401, 403);
    assertThat(get("/pdf-exports/" + id, false).statusCode()).isIn(401, 403);
    assertThat(get("/pdf-exports/" + id + "/file", false).statusCode()).isIn(401, 403);
  }

  @Test
  void thereIsNoLimitOnHowManyAPersonMayHaveOpen() throws Exception {
    for (int i = 0; i < 8; i++) {
      created(
          "{\"pages\":\""
              + (i % 3 + 1)
              + "\",\"variant\":\""
              + (i < 3 ? "original" : i < 6 ? "english" : "side-by-side")
              + "\"}",
          202);
    }
    // Eight requests, distinct selections or variants: each is accepted.
    assertThat(exports()).isGreaterThanOrEqualTo(7L);
  }

  // --- deleting a record -------------------------------------------------------------------

  @Test
  void deletingARecordRemovesItsExportFilesAndRows() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    String path = jdbc.queryForObject("SELECT path FROM pdf_export", String.class);
    Path file = storageRoot.resolve(path);
    assertThat(file).exists();

    ingestService.deleteRecord(record);

    assertThat(file).doesNotExist();
    assertThat(exports()).isZero();
    assertThat(get("/pdf-exports/" + id, true).statusCode()).isEqualTo(404);
  }

  // --- the old paths are gone --------------------------------------------------------------

  @Test
  void theSynchronousPdfUrlsNoLongerExist() throws Exception {
    // both synchronous addresses are gone altogether
    assertThat(get("/records/" + record + "/pdf", true).statusCode()).isEqualTo(404);
    assertThat(get("/records/" + record + "/export-pdf?pages=1-3", true).statusCode())
        .isEqualTo(404);
  }

  @Test
  void theMachineApiPointsAtTheExportAddress() throws Exception {
    JsonNode doc = json.readTree(get("/v1/documents/" + record, true).body());

    assertThat(doc.get("links").has("pdf")).isFalse();
    assertThat(doc.get("links").get("pdfExport").asText())
        .endsWith("/records/" + record + "/pdf-exports");
  }

  @Test
  void aRecordWithNoPagesOffersNoExportAddress() throws Exception {
    long empty = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Empty");

    JsonNode doc = json.readTree(get("/v1/documents/" + empty, true).body());

    assertThat(doc.get("links").has("pdfExport")).isFalse();
  }
}
