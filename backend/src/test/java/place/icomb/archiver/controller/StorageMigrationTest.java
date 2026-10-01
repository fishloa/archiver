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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

/**
 * Page images move from record-and-sequence paths to attachment addresses, and nothing is lost on
 * the way. Moving pages between records is only a database statement once this has run to zero.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorageMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;
  @Autowired private Path storageRoot;

  private String base;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN_EMAIL = "storage-migration-admin@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    base = "http://localhost:" + port + "/api";
    // The counts under test are global, so each test starts from an empty catalogue.
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM record").update();

    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'StorageMigrationAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('StorageMigrationAdmin',"
                    + " 'admin') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  // --- helpers -----------------------------------------------------------------------------

  private long newRecord() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:n, 'AT') RETURNING id")
            .param("n", "Storage Migration Archive " + UUID.randomUUID())
            .query(Long.class)
            .single();
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

  /** A page image exactly as the old code stored it: a file, and a row naming it by path. */
  private long legacyPage(long recordId, int seq, String path, String content) throws Exception {
    if (content != null) {
      Path file = storageRoot.resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    }
    return jdbc.sql(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (:r, 'page_image',"
                + " :p, 'image/jpeg') RETURNING id")
        .param("r", recordId)
        .param("p", path)
        .query(Long.class)
        .single();
  }

  private String pathOf(long attachmentId) {
    return jdbc.sql("SELECT path FROM attachment WHERE id = :id")
        .param("id", attachmentId)
        .query(String.class)
        .single();
  }

  private JsonNode migrate(String body) throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/storage/migrate"))
                .header("Content-Type", "application/json")
                .header("X-Auth-Email", ADMIN_EMAIL)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    return json.readTree(resp.body());
  }

  private JsonNode status() throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/storage/migrate"))
                .header("X-Auth-Email", ADMIN_EMAIL)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    return json.readTree(resp.body());
  }

  private long uploadPage(long recordId, int seq, String content) throws Exception {
    String boundary = "----B" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"p.jpg\"\r\nContent-Type:"
                + " image/jpeg\r\n\r\n")
            .formatted(boundary)
            .getBytes(StandardCharsets.UTF_8));
    out.write(content.getBytes(StandardCharsets.UTF_8));
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
    return json.readTree(resp.body()).get("attachmentId").asLong();
  }

  // --- new writes --------------------------------------------------------------------------

  @Test
  void aNewPageImageIsStoredAtAnAttachmentAddress() throws Exception {
    long record = newRecord();

    long attachmentId = uploadPage(record, 1, "fresh scan");

    String path = pathOf(attachmentId);
    assertThat(path).matches("attachments/[0-9a-f]{2}/[0-9a-f-]{36}\\.jpg");
    assertThat(path).doesNotContain("records/");
    assertThat(Files.readString(storageRoot.resolve(path))).isEqualTo("fresh scan");
  }

  // --- the backfill ------------------------------------------------------------------------

  @Test
  void movesALegacyFileAndPointsTheRowAtIt() throws Exception {
    long record = newRecord();
    String old = "records/%d/attachments/pages/p0001-abcd1234.jpg".formatted(record);
    long id = legacyPage(record, 1, old, "the old scan");

    JsonNode batch = migrate("{\"limit\":10}");

    assertThat(batch.get("migrated").asInt()).isEqualTo(1);
    String now = pathOf(id);
    assertThat(now).startsWith("attachments/");
    assertThat(Files.readString(storageRoot.resolve(now))).isEqualTo("the old scan");
    assertThat(storageRoot.resolve(old)).doesNotExist();
  }

  @Test
  void runningItTwiceMovesNothingTheSecondTime() throws Exception {
    long record = newRecord();
    long id =
        legacyPage(record, 1, "records/%d/attachments/pages/p0001.jpg".formatted(record), "scan");

    migrate("{}");
    String after = pathOf(id);
    JsonNode second = migrate("{}");

    assertThat(second.get("migrated").asInt()).isZero();
    assertThat(pathOf(id)).isEqualTo(after);
    assertThat(Files.readString(storageRoot.resolve(after))).isEqualTo("scan");
  }

  @Test
  void aSmallLimitWalksTheBacklogAcrossCallsWithoutMovingARowTwice() throws Exception {
    long record = newRecord();
    for (int seq = 1; seq <= 3; seq++) {
      legacyPage(
          record,
          seq,
          "records/%d/attachments/pages/p%04d.jpg".formatted(record, seq),
          "scan " + seq);
    }

    JsonNode first = migrate("{\"limit\":2}");
    assertThat(first.get("migrated").asInt()).isEqualTo(2);
    assertThat(first.get("status").get("legacy").asLong()).isEqualTo(1);

    JsonNode second = migrate("{\"limit\":2}");
    assertThat(second.get("migrated").asInt()).isEqualTo(1);
    assertThat(second.get("status").get("legacy").asLong()).isZero();
    assertThat(status().get("migrated").asLong()).isEqualTo(3);
  }

  @Test
  void aRowWhoseFileIsGoneIsCountedLeftAloneAndDoesNotStopTheBatch() throws Exception {
    long record = newRecord();
    String gone = "records/%d/attachments/pages/p0001.jpg".formatted(record);
    long goneId = legacyPage(record, 1, gone, null);
    long okId =
        legacyPage(record, 2, "records/%d/attachments/pages/p0002.jpg".formatted(record), "ok");

    JsonNode batch = migrate("{}");

    assertThat(batch.get("missing").asInt()).isEqualTo(1);
    assertThat(batch.get("migrated").asInt()).isEqualTo(1);
    assertThat(pathOf(goneId)).isEqualTo(gone);
    assertThat(pathOf(okId)).startsWith("attachments/");

    // Counted as missing, and no longer counted as work to do — so it is neither retried for ever
    // nor able to hold the gate shut.
    JsonNode status = status();
    assertThat(status.get("missing").asLong()).isEqualTo(1);
    assertThat(status.get("legacy").asLong()).isZero();
  }

  @Test
  void aFileTwoRowsShareIsKeptUntilTheSecondRowHasMoved() throws Exception {
    // The collision that cost record 4006 its images left rows naming one file. Deleting the
    // original after the first of them moved would take the other's scan with it.
    long record = newRecord();
    String shared = "records/%d/attachments/pages/p0001.jpg".formatted(record);
    long a = legacyPage(record, 1, shared, "shared scan");
    long b = legacyPage(record, 2, shared, null);

    migrate("{\"limit\":1}");
    assertThat(storageRoot.resolve(shared)).exists();

    migrate("{\"limit\":1}");
    assertThat(Files.readString(storageRoot.resolve(pathOf(a)))).isEqualTo("shared scan");
    assertThat(Files.readString(storageRoot.resolve(pathOf(b)))).isEqualTo("shared scan");
    assertThat(pathOf(a)).isNotEqualTo(pathOf(b));
    assertThat(storageRoot.resolve(shared)).doesNotExist();
  }

  @Test
  void onlyPageImagesAreTouched() throws Exception {
    long record = newRecord();
    String pdf = "records/%d/attachments/record.pdf".formatted(record);
    Files.createDirectories(storageRoot.resolve(pdf).getParent());
    Files.writeString(storageRoot.resolve(pdf), "pdf");
    long pdfId =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path) VALUES (:r, 'original_pdf', :p)"
                    + " RETURNING id")
            .param("r", record)
            .param("p", pdf)
            .query(Long.class)
            .single();

    migrate("{}");

    assertThat(pathOf(pdfId)).isEqualTo(pdf);
    assertThat(storageRoot.resolve(pdf)).exists();
  }

  @Test
  void statusCountsWhatIsLeftAndWhatIsDone() throws Exception {
    long record = newRecord();
    legacyPage(record, 1, "records/%d/attachments/pages/p0001.jpg".formatted(record), "a");
    uploadPage(record, 2, "b");

    JsonNode before = status();
    assertThat(before.get("legacy").asLong()).isEqualTo(1);
    assertThat(before.get("migrated").asLong()).isEqualTo(1);
  }

  @Test
  void anAbsurdLimitIsRefused() throws Exception {
    for (String body : List.of("{\"limit\":0}", "{\"limit\":-3}", "{\"limit\":99999999}")) {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(base + "/admin/storage/migrate"))
                  .header("Content-Type", "application/json")
                  .header("X-Auth-Email", ADMIN_EMAIL)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(resp.statusCode()).as(body).isEqualTo(400);
    }
  }

  @Test
  void migrationIsForAdministratorsOnly() throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/storage/migrate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isIn(401, 403);
  }

  // --- deleting ----------------------------------------------------------------------------

  @Test
  void deletingARecordRemovesItsPageImagesWhereverTheyLive() throws Exception {
    // Page images no longer sit under records/{id}/, so removing that directory would leak every
    // scan the record owned, for ever.
    long record = newRecord();
    long a = uploadPage(record, 1, "one");
    long b = uploadPage(record, 2, "two");
    Path one = storageRoot.resolve(pathOf(a));
    Path two = storageRoot.resolve(pathOf(b));
    assertThat(one).exists();
    assertThat(two).exists();

    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + record))
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isIn(200, 204);
    assertThat(one).doesNotExist();
    assertThat(two).doesNotExist();
  }

  @Test
  void deletingAPageRemovesItsImageAndLeavesItsNeighboursAlone() throws Exception {
    long record = newRecord();
    long a = uploadPage(record, 1, "one");
    long b = uploadPage(record, 2, "two");
    Path one = storageRoot.resolve(pathOf(a));
    Path two = storageRoot.resolve(pathOf(b));

    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/records/" + record + "/pages/1"))
                .header("X-Auth-Email", ADMIN_EMAIL)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(one).doesNotExist();
    assertThat(Files.readString(two)).isEqualTo("two");
  }

  @Test
  void deletingARecordStillClearsLegacyFilesUnderItsDirectory() throws Exception {
    long record = newRecord();
    String old = "records/%d/attachments/pages/p0001.jpg".formatted(record);
    legacyPage(record, 1, old, "old scan");

    http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + "/ingest/records/" + record))
            .header("Authorization", PROCESSOR_AUTH_HEADER)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(storageRoot.resolve(old)).doesNotExist();
  }

  // --- the retired stored PDFs -------------------------------------------------------------

  private long storedPdf(long recordId, String role, String path, String content, boolean point)
      throws Exception {
    if (content != null) {
      Path file = storageRoot.resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    }
    long id =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path, mime, bytes) VALUES (:r, :role,"
                    + " :p, 'application/pdf', 10) RETURNING id")
            .param("r", recordId)
            .param("role", role)
            .param("p", path)
            .query(Long.class)
            .single();
    if (point) {
      jdbc.sql("UPDATE record SET pdf_attachment_id = :a WHERE id = :r")
          .param("a", id)
          .param("r", recordId)
          .update();
    }
    return id;
  }

  private HttpResponse<String> adminPost(String path, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void purgingStoredPdfsRemovesTheRowsTheFilesAndThePointerButNothingElse() throws Exception {
    long record = newRecord();
    long pdf =
        storedPdf(
            record,
            "searchable_pdf",
            "records/%d/attachments/record.pdf".formatted(record),
            "pdf",
            true);
    long born =
        storedPdf(
            record,
            "original_pdf",
            "records/%d/attachments/original.pdf".formatted(record),
            "orig",
            false);
    long image = legacyPage(record, 1, "attachments/aa/keep.jpg", "scan");

    JsonNode before =
        json.readTree(
            http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(base + "/admin/storage/searchable-pdfs"))
                        .header("X-Auth-Email", ADMIN_EMAIL)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(before.get("files").asLong()).isEqualTo(1);
    assertThat(before.get("recordsPointing").asLong()).isEqualTo(1);

    HttpResponse<String> resp = adminPost("/admin/storage/purge-searchable-pdfs", "{}");
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
    JsonNode out = json.readTree(resp.body());
    assertThat(out.get("purged").asInt()).isEqualTo(1);
    assertThat(out.get("filesRemoved").asInt()).isEqualTo(1);
    assertThat(out.get("status").get("files").asLong()).isZero();

    assertThat(storageRoot.resolve("records/%d/attachments/record.pdf".formatted(record)))
        .doesNotExist();
    assertThat(
            jdbc.sql("SELECT count(*) FROM attachment WHERE id = :i")
                .param("i", pdf)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT pdf_attachment_id FROM record WHERE id = :r")
                .param("r", record)
                .query(Long.class)
                .optional())
        .isEmpty();
    // a born-digital upload's own PDF and every page image are left alone
    assertThat(storageRoot.resolve("records/%d/attachments/original.pdf".formatted(record)))
        .exists();
    assertThat(storageRoot.resolve("attachments/aa/keep.jpg")).exists();
    assertThat(
            jdbc.sql("SELECT count(*) FROM attachment WHERE id IN (:a, :b)")
                .param("a", born)
                .param("b", image)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void aSmallLimitWalksThePurgeAcrossCalls() throws Exception {
    long record = newRecord();
    for (int i = 0; i < 3; i++) {
      storedPdf(
          record, "searchable_pdf", "records/%d/x/%d.pdf".formatted(record, i), "p" + i, false);
    }
    assertThat(
            json.readTree(adminPost("/admin/storage/purge-searchable-pdfs", "{\"limit\":2}").body())
                .get("purged")
                .asInt())
        .isEqualTo(2);
    JsonNode second =
        json.readTree(adminPost("/admin/storage/purge-searchable-pdfs", "{\"limit\":2}").body());
    assertThat(second.get("purged").asInt()).isEqualTo(1);
    assertThat(second.get("status").get("files").asLong()).isZero();
  }

  @Test
  void aFileAnotherRowStillNamesIsKept() throws Exception {
    long record = newRecord();
    String shared = "records/%d/attachments/record.pdf".formatted(record);
    storedPdf(record, "searchable_pdf", shared, "shared", false);
    storedPdf(record, "original_pdf", shared, null, false);

    JsonNode out = json.readTree(adminPost("/admin/storage/purge-searchable-pdfs", "{}").body());
    assertThat(out.get("purged").asInt()).isEqualTo(1);
    assertThat(out.get("filesRemoved").asInt()).isZero();
    assertThat(out.get("sharedPathKept").asInt()).isEqualTo(1);
    assertThat(storageRoot.resolve(shared)).exists();
  }

  @Test
  void aBadPurgeLimitIsARefusal() throws Exception {
    assertThat(adminPost("/admin/storage/purge-searchable-pdfs", "{\"limit\":0}").statusCode())
        .isEqualTo(400);
    assertThat(adminPost("/admin/storage/purge-searchable-pdfs", "{\"limit\":\"x\"}").statusCode())
        .isEqualTo(400);
  }
}
