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

  @BeforeEach
  void setUp() {
    base = "http://localhost:" + port + "/api";
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
}
