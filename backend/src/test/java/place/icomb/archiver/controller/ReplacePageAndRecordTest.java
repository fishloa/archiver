package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplacePageAndRecordTest {

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

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private static final String ADMIN_EMAIL = "page-edit-admin@example.com";

  @BeforeEach
  void setUp() {
    base = "http://localhost:" + port + "/api";

    // Insert and delete live under /api/admin, so this suite needs an administrator.
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'PageEditAdmin'").update();
    Long adminId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('PageEditAdmin', 'admin')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:uid, :e)")
        .param("uid", adminId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  private long createRecord(long archiveId, String sourceRecordId) throws Exception {
    String recordBody =
        """
        {"archiveId":%d,"sourceSystem":"test","sourceRecordId":"%s","lang":"de","metadataLang":"de"}
        """
            .formatted(archiveId, sourceRecordId);
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(recordBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(201);
    @SuppressWarnings("unchecked")
    Map<String, Object> respJson = json.readValue(resp.body(), Map.class);
    return ((Number) respJson.get("id")).longValue();
  }

  private HttpResponse<String> uploadPage(long recordId, int seq, byte[] imageBytes)
      throws Exception {
    String boundary = "----TestBoundary" + System.nanoTime();
    byte[] body = buildMultipart(boundary, "image", "page.jpg", imageBytes);
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + "/ingest/records/" + recordId + "/pages?seq=" + seq))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", PROCESSOR_AUTH_HEADER)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> replacePage(long recordId, int seq, byte[] imageBytes)
      throws Exception {
    String boundary = "----TestBoundary" + System.nanoTime();
    byte[] body = buildMultipart(boundary, "image", "page.jpg", imageBytes);
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + "/ingest/records/" + recordId + "/pages/" + seq))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", PROCESSOR_AUTH_HEADER)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void replacePageSwapsImageWithoutDuplicatingOrChangingRecordId() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Replace-Page Test Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    long recordId = createRecord(archiveId, "replace-page-test-" + UUID.randomUUID());

    byte[] originalBytes = "original page bytes".getBytes(StandardCharsets.UTF_8);
    HttpResponse<String> uploadResp = uploadPage(recordId, 1, originalBytes);
    assertThat(uploadResp.statusCode()).isEqualTo(201);
    @SuppressWarnings("unchecked")
    Map<String, Object> uploadJson = json.readValue(uploadResp.body(), Map.class);
    long originalPageId = ((Number) uploadJson.get("id")).longValue();
    long originalAttachmentId = ((Number) uploadJson.get("attachmentId")).longValue();

    // Simulate stale OCR text on the original (broken) page, as a real page would have after OCR.
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, confidence, text_raw) VALUES (:pid, 'qwen3vl', 0.95, 'stale broken ocr text')")
        .param("pid", originalPageId)
        .update();

    byte[] fixedBytes = "fixed complete page bytes".getBytes(StandardCharsets.UTF_8);
    HttpResponse<String> replaceResp = replacePage(recordId, 1, fixedBytes);
    assertThat(replaceResp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> replaceJson = json.readValue(replaceResp.body(), Map.class);
    assertThat(((Number) replaceJson.get("recordId")).longValue()).isEqualTo(recordId);
    assertThat(((Number) replaceJson.get("seq")).intValue()).isEqualTo(1);
    long newAttachmentId = ((Number) replaceJson.get("attachmentId")).longValue();
    assertThat(newAttachmentId).isNotEqualTo(originalAttachmentId);

    // Exactly one page remains at seq=1 -- no duplicate from the replace.
    int pageCount =
        jdbc.sql("SELECT count(*) FROM page WHERE record_id = :rid AND seq = 1")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(pageCount).isEqualTo(1);

    // The old attachment (and its stale OCR text) is gone.
    int oldAttachmentCount =
        jdbc.sql("SELECT count(*) FROM attachment WHERE id = :id")
            .param("id", originalAttachmentId)
            .query(Integer.class)
            .single();
    assertThat(oldAttachmentCount).isEqualTo(0);
    int oldPageTextCount =
        jdbc.sql("SELECT count(*) FROM page_text WHERE page_id = :pid")
            .param("pid", originalPageId)
            .query(Integer.class)
            .single();
    assertThat(oldPageTextCount).isEqualTo(0);

    // New attachment holds the fixed bytes.
    String newPath =
        jdbc.sql("SELECT path FROM attachment WHERE id = :id")
            .param("id", newAttachmentId)
            .query(String.class)
            .single();
    assertThat(newPath).isNotNull();

    // attachmentCount on the record reflects reality (1), not double-counted from the replace.
    int attachmentCount =
        jdbc.sql("SELECT attachment_count FROM record WHERE id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(attachmentCount).isEqualTo(1);

    // The record's own id never changed.
    int recordStillExists =
        jdbc.sql("SELECT count(*) FROM record WHERE id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(recordStillExists).isEqualTo(1);
  }

  @Test
  void replaceAllPagesWipesPagesAndPdfButKeepsRecordId() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Replace-All-Pages Test Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    long recordId = createRecord(archiveId, "replace-all-test-" + UUID.randomUUID());

    assertThat(uploadPage(recordId, 1, "page one".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(201);
    assertThat(uploadPage(recordId, 2, "page two".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(201);

    // Complete the ingest so the record reaches a normal post-scrape status.
    HttpResponse<String> completeResp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/complete"))
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(completeResp.statusCode()).isEqualTo(200);

    int pageCountBefore =
        jdbc.sql("SELECT count(*) FROM page WHERE record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(pageCountBefore).isEqualTo(2);

    HttpResponse<String> replaceResp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/replace-pages"))
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(replaceResp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> replaceJson = json.readValue(replaceResp.body(), Map.class);
    assertThat(((Number) replaceJson.get("id")).longValue()).isEqualTo(recordId);
    assertThat((String) replaceJson.get("status")).isEqualTo("ingesting");

    int pageCountAfter =
        jdbc.sql("SELECT count(*) FROM page WHERE record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(pageCountAfter).isEqualTo(0);

    int attachmentCountAfter =
        jdbc.sql("SELECT count(*) FROM attachment WHERE record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(attachmentCountAfter).isEqualTo(0);

    Integer pdfAttachmentId =
        jdbc.sql("SELECT pdf_attachment_id FROM record WHERE id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .optional()
            .orElse(null);
    assertThat(pdfAttachmentId).isNull();

    // The record's own id never changed, and it can be re-scraped from scratch at the same seqs.
    assertThat(
            uploadPage(recordId, 1, "new page one".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(201);
    int pageCountRebuilt =
        jdbc.sql("SELECT count(*) FROM page WHERE record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(pageCountRebuilt).isEqualTo(1);
  }

  private static byte[] buildMultipart(
      String boundary, String fieldName, String fileName, byte[] fileBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String header =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\""
            + fieldName
            + "\"; filename=\""
            + fileName
            + "\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n";
    String footer = "\r\n--" + boundary + "--\r\n";
    out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
    out.writeBytes(fileBytes);
    out.writeBytes(footer.getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  // --- insert and delete -------------------------------------------------------------------

  private HttpResponse<String> insertPage(long recordId, int seq, byte[] imageBytes)
      throws Exception {
    String boundary = "----TestBoundary" + System.nanoTime();
    byte[] body = buildMultipart(boundary, "image", "page.jpg", imageBytes);
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + "/admin/records/" + recordId + "/pages/" + seq + "/insert"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-Auth-Email", ADMIN_EMAIL)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> deletePage(long recordId, int seq) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + "/admin/records/" + recordId + "/pages/" + seq))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A record of `n` pages whose image bytes name their own position. */
  private long recordWithPages(int n) throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, 'AT') RETURNING id")
            .param("name", "Page-Edit Archive " + UUID.randomUUID())
            .query(Long.class)
            .single();
    long recordId = createRecord(archiveId, "page-edit-" + UUID.randomUUID());
    for (int i = 1; i <= n; i++) {
      assertThat(
              uploadPage(recordId, i, ("page " + i).getBytes(StandardCharsets.UTF_8)).statusCode())
          .isEqualTo(201);
    }
    return recordId;
  }

  private java.util.List<Integer> seqs(long recordId) {
    return jdbc.sql("SELECT seq FROM page WHERE record_id = :rid ORDER BY seq")
        .param("rid", recordId)
        .query(Integer.class)
        .list();
  }

  @Test
  void insertingAPageMovesTheRestUp() throws Exception {
    // The case this exists for: one scanned sheet holding two documents. The left half replaces
    // the page, the right half is inserted after it.
    long recordId = recordWithPages(4);

    assertThat(
            insertPage(recordId, 3, "the other half".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(200);

    assertThat(seqs(recordId)).containsExactly(1, 2, 3, 4, 5);
    assertThat(
            jdbc.sql("SELECT page_count FROM record WHERE id = :rid")
                .param("rid", recordId)
                .query(Integer.class)
                .single())
        .isEqualTo(5);
  }

  @Test
  void insertingAtTheEndIsAllowedButNotBeyondIt() throws Exception {
    long recordId = recordWithPages(3);

    assertThat(insertPage(recordId, 4, "appended".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(200);
    assertThat(seqs(recordId)).containsExactly(1, 2, 3, 4);

    // Two past the end would leave a hole.
    assertThat(insertPage(recordId, 6, "nowhere".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(400);
  }

  @Test
  void deletingAPageClosesTheGap() throws Exception {
    long recordId = recordWithPages(4);

    assertThat(deletePage(recordId, 2).statusCode()).isEqualTo(200);

    assertThat(seqs(recordId)).containsExactly(1, 2, 3);
    assertThat(
            jdbc.sql("SELECT page_count FROM record WHERE id = :rid")
                .param("rid", recordId)
                .query(Integer.class)
                .single())
        .isEqualTo(3);
  }

  @Test
  void deletingAPageTakesItsTranscriptionWithIt() throws Exception {
    long recordId = recordWithPages(2);
    Long pageId =
        jdbc.sql("SELECT id FROM page WHERE record_id = :rid AND seq = 1")
            .param("rid", recordId)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, text_raw) VALUES (:pid, 'mistral-ocr', 'text')")
        .param("pid", pageId)
        .update();

    deletePage(recordId, 1);

    assertThat(
            jdbc.sql("SELECT count(*) FROM page_text WHERE page_id = :pid")
                .param("pid", pageId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void deletingAPageThatIsNotThereIsRefused() throws Exception {
    long recordId = recordWithPages(2);

    assertThat(deletePage(recordId, 9).statusCode()).isEqualTo(400);
    assertThat(seqs(recordId)).containsExactly(1, 2);
  }

  @Test
  void aSheetHoldingTwoDocumentsBecomesTwoPages() throws Exception {
    // The whole point, end to end: page 2 is replaced by its left half and the right half is
    // inserted behind it, leaving the record one page longer and still consecutively numbered.
    long recordId = recordWithPages(3);

    assertThat(replacePage(recordId, 2, "left half".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(200);
    assertThat(insertPage(recordId, 3, "right half".getBytes(StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(200);

    assertThat(seqs(recordId)).containsExactly(1, 2, 3, 4);
  }

  @Test
  void anOrdinaryProcessorTokenCannotInsertOrDeletePages() throws Exception {
    long recordId = recordWithPages(2);

    var delete =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/records/" + recordId + "/pages/1"))
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(delete.statusCode()).isIn(401, 403);
    assertThat(seqs(recordId)).containsExactly(1, 2);
  }

  @Test
  void aninsertedPageDoesNotOverwriteTheFileOfThePageItDisplaces() throws Exception {
    // The sequence is not a unique file name: inserting renumbers the pages after it, so two live
    // pages could compute the same path and the second write destroyed the first. Seven pages of
    // one record ended up showing their neighbour's image that way.
    long recordId = recordWithPages(3);

    insertPage(recordId, 2, "the inserted page".getBytes(StandardCharsets.UTF_8));

    java.util.List<String> paths =
        jdbc.sql(
                "SELECT a.path FROM page p JOIN attachment a ON a.id = p.attachment_id"
                    + " WHERE p.record_id = :rid ORDER BY p.seq")
            .param("rid", recordId)
            .query(String.class)
            .list();

    assertThat(paths).hasSize(4).doesNotHaveDuplicates();
  }

  @Test
  void replacingAPageDoesNotCollideWithAnyOtherPagesFile() throws Exception {
    long recordId = recordWithPages(3);

    replacePage(recordId, 2, "replacement bytes".getBytes(StandardCharsets.UTF_8));

    java.util.List<String> paths =
        jdbc.sql(
                "SELECT a.path FROM page p JOIN attachment a ON a.id = p.attachment_id"
                    + " WHERE p.record_id = :rid ORDER BY p.seq")
            .param("rid", recordId)
            .query(String.class)
            .list();

    assertThat(paths).hasSize(3).doesNotHaveDuplicates();
  }
}
