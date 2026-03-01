package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
class TextPdfIngestTest {

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

  @Test
  void textPdfUploadExtractsTextAndSkipsOcr() throws Exception {
    // Seed an archive
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Text-PDF Test Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    // Create a record
    String sourceRecordId = "text-pdf-test-" + UUID.randomUUID();
    String recordBody =
        """
        {"archiveId":%d,"sourceSystem":"test","sourceRecordId":"%s","lang":"de","metadataLang":"de"}
        """
            .formatted(archiveId, sourceRecordId);
    HttpResponse<String> createResp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(recordBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(createResp.statusCode()).isEqualTo(201);
    @SuppressWarnings("unchecked")
    Map<String, Object> createJson = json.readValue(createResp.body(), Map.class);
    long recordId = ((Number) createJson.get("id")).longValue();

    // Build a 3-page text PDF with PDFBox
    byte[] pdfBytes = buildTestPdf(3);

    // Upload via text-pdf endpoint (multipart)
    String boundary = "----TestBoundary" + System.currentTimeMillis();
    byte[] multipartBody = buildMultipart(boundary, "pdf", "test.pdf", pdfBytes);

    HttpResponse<String> uploadResp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/text-pdf"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(uploadResp.statusCode()).isEqualTo(201);
    @SuppressWarnings("unchecked")
    Map<String, Object> uploadJson = json.readValue(uploadResp.body(), Map.class);
    assertThat(((Number) uploadJson.get("pages")).intValue()).isEqualTo(3);
    assertThat((Boolean) uploadJson.get("ocrSkipped")).isTrue();

    // Verify pages were created
    int pageCount =
        jdbc.sql("SELECT count(*) FROM page WHERE record_id = :rid")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(pageCount).isEqualTo(3);

    // Verify page_text was created for all pages with engine=pdfbox
    int textCount =
        jdbc.sql(
                "SELECT count(*) FROM page_text pt JOIN page p ON pt.page_id = p.id WHERE p.record_id = :rid AND pt.engine = 'pdfbox'")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(textCount).isEqualTo(3);

    // Complete the ingest — should skip OCR since all pages have text
    HttpResponse<String> completeResp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(base + "/ingest/records/" + recordId + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(completeResp.statusCode()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> completeJson = json.readValue(completeResp.body(), Map.class);
    // Status should be ocr_pending but with 0 OCR jobs enqueued (all skipped)
    String status = (String) completeJson.get("status");
    assertThat(status).isIn("ocr_pending", "ocr_done");

    // Verify no OCR jobs were enqueued
    int ocrJobs =
        jdbc.sql(
                "SELECT count(*) FROM job WHERE record_id = :rid AND kind = 'ocr_page_paddle' AND status != 'completed'")
            .param("rid", recordId)
            .query(Integer.class)
            .single();
    assertThat(ocrJobs).isEqualTo(0);
  }

  private static byte[] buildTestPdf(int pages) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      for (int i = 1; i <= pages; i++) {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          cs.beginText();
          cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          cs.newLineAtOffset(72, 700);
          cs.showText("This is test page " + i + " with some sample text for extraction.");
          cs.newLineAtOffset(0, -20);
          cs.showText("It contains multiple lines to verify PDFBox text extraction works.");
          cs.endText();
        }
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      doc.save(baos);
      return baos.toByteArray();
    }
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
            + "Content-Type: application/pdf\r\n\r\n";
    String footer = "\r\n--" + boundary + "--\r\n";
    byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
    byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(headerBytes);
    out.writeBytes(fileBytes);
    out.writeBytes(footerBytes);
    return out.toByteArray();
  }
}
