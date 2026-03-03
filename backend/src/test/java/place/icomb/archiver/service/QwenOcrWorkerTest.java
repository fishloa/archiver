package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
class QwenOcrWorkerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  static WireMockServer wireMock =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  static {
    wireMock.start();
  }

  @Autowired private QwenOcrWorker worker;
  @Autowired private JdbcClient jdbc;

  @Value("${archiver.storage.root}")
  private String storageRoot;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.ocr.qwen.enabled", () -> "true");
    registry.add("archiver.ocr.qwen.ollama-url", wireMock::baseUrl);
    registry.add("archiver.ocr.qwen.model", () -> "test-model");
    registry.add("archiver.ocr.qwen.poll-interval", () -> "999999999");
  }

  @AfterAll
  static void tearDown() {
    wireMock.stop();
  }

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    jdbc.sql("DELETE FROM pipeline_event").update();
    jdbc.sql("DELETE FROM text_chunk").update();
    jdbc.sql("DELETE FROM job").update();
    jdbc.sql("DELETE FROM page_text").update();
    jdbc.sql("DELETE FROM page").update();
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM attachment").update();
    jdbc.sql("DELETE FROM record").update();
  }

  @Test
  void claimsJobAndInsertsPageText() {
    wireMock.stubFor(
        post(urlEqualTo("/api/generate"))
            .willReturn(
                ok().withHeader("Content-Type", "application/json")
                    .withBody("{\"response\":\"Hello World OCR text\",\"done\":true}")));

    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "complete", 1);
    String imgPath = "records/" + recordId + "/attachments/pages/p0001.jpg";
    Long attachmentId = createAttachment(recordId, "page_image", imgPath);
    Long pageId = createPage(recordId, 1, attachmentId);
    Long jobId = createJob(recordId, pageId, "ocr_page_qwen3vl", "pending");
    createTestImageFile(imgPath);

    worker.pollAndProcess();

    assertThat(getJobStatus(jobId)).isEqualTo("completed");

    String text =
        jdbc.sql("SELECT text_raw FROM page_text WHERE page_id = :pid AND engine = 'qwen3vl'")
            .param("pid", pageId)
            .query(String.class)
            .single();
    assertThat(text).isEqualTo("Hello World OCR text");
  }

  @Test
  void failsJobOnOllamaError() {
    wireMock.stubFor(
        post(urlEqualTo("/api/generate")).willReturn(serverError().withBody("model not found")));

    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "complete", 1);
    String imgPath = "records/" + recordId + "/attachments/pages/p0001.jpg";
    Long attachmentId = createAttachment(recordId, "page_image", imgPath);
    Long pageId = createPage(recordId, 1, attachmentId);
    Long jobId = createJob(recordId, pageId, "ocr_page_qwen3vl", "pending");
    createTestImageFile(imgPath);

    worker.pollAndProcess();

    assertThat(getJobStatus(jobId)).isEqualTo("failed");

    long textCount =
        jdbc.sql("SELECT count(*) FROM page_text WHERE page_id = :pid AND engine = 'qwen3vl'")
            .param("pid", pageId)
            .query(Long.class)
            .single();
    assertThat(textCount).isZero();
  }

  @Autowired private JobService jobService;

  @Test
  void resetAndOcrTriggersFullPipeline() {
    wireMock.stubFor(
        post(urlEqualTo("/api/generate"))
            .willReturn(
                ok().withHeader("Content-Type", "application/json")
                    .withBody("{\"response\":\"Qwen OCR text\",\"done\":true}")));

    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "complete", 2);

    // Create 2 pages with existing paddle page_text
    String imgPath1 = "records/" + recordId + "/attachments/pages/p0001.jpg";
    Long att1 = createAttachment(recordId, "page_image", imgPath1);
    Long page1 = createPage(recordId, 1, att1);
    createTestImageFile(imgPath1);
    createPageText(page1, "paddle", "Old paddle text 1");

    String imgPath2 = "records/" + recordId + "/attachments/pages/p0002.jpg";
    Long att2 = createAttachment(recordId, "page_image", imgPath2);
    Long page2 = createPage(recordId, 2, att2);
    createTestImageFile(imgPath2);
    createPageText(page2, "paddle", "Old paddle text 2");

    // Reset record to ocr_pending (cleans old data)
    jobService.resetForOcr(recordId);
    String statusAfterReset =
        jdbc.sql("SELECT status FROM record WHERE id = :id")
            .param("id", recordId)
            .query(String.class)
            .single();
    assertThat(statusAfterReset).isEqualTo("ocr_pending");

    // Old paddle page_text should be deleted by resetForOcr
    long paddleCount =
        jdbc.sql(
                "SELECT count(*) FROM page_text WHERE engine = 'paddle' AND page_id IN (SELECT id FROM page WHERE record_id = :rid)")
            .param("rid", recordId)
            .query(Long.class)
            .single();
    assertThat(paddleCount).isZero();

    // Enqueue 2 Qwen jobs
    Long job1 = createJob(recordId, page1, "ocr_page_qwen3vl", "pending");
    Long job2 = createJob(recordId, page2, "ocr_page_qwen3vl", "pending");

    // Process first job — should NOT trigger pipeline yet (job2 still pending)
    worker.pollAndProcess();
    String statusAfterFirst =
        jdbc.sql("SELECT status FROM record WHERE id = :id")
            .param("id", recordId)
            .query(String.class)
            .single();
    assertThat(statusAfterFirst).isEqualTo("ocr_pending");

    // Process second job — checkRecordOcrComplete triggers naturally
    worker.pollAndProcess();

    // Record should now be in pdf_pending (post-OCR pipeline started)
    String statusAfterSecond =
        jdbc.sql("SELECT status FROM record WHERE id = :id")
            .param("id", recordId)
            .query(String.class)
            .single();
    assertThat(statusAfterSecond).isEqualTo("pdf_pending");

    // Qwen page_text should exist for both pages
    long qwenCount =
        jdbc.sql(
                "SELECT count(*) FROM page_text WHERE engine = 'qwen3vl' AND page_id IN (SELECT id FROM page WHERE record_id = :rid)")
            .param("rid", recordId)
            .query(Long.class)
            .single();
    assertThat(qwenCount).isEqualTo(2);

    // build_searchable_pdf job should be enqueued
    long pdfJobs =
        jdbc.sql(
                "SELECT count(*) FROM job WHERE record_id = :rid AND kind = 'build_searchable_pdf' AND status = 'pending'")
            .param("rid", recordId)
            .query(Long.class)
            .single();
    assertThat(pdfJobs).isEqualTo(1);
  }

  @Test
  void doesNothingWhenNoJobsPending() {
    worker.pollAndProcess();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Long createArchive() {
    return jdbc.sql("INSERT INTO archive (name, country) VALUES ('Test', 'CZ') RETURNING id")
        .query(Long.class)
        .single();
  }

  private Long createRecord(Long archiveId, String status, int pageCount) {
    return jdbc.sql(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                attachment_count, created_at, updated_at)
            VALUES (:aid, 'test', :srcId, :status, :pc, 0, now(), now())
            RETURNING id
            """)
        .param("aid", archiveId)
        .param("srcId", "REC-" + System.nanoTime())
        .param("status", status)
        .param("pc", pageCount)
        .query(Long.class)
        .single();
  }

  private Long createAttachment(Long recordId, String role, String path) {
    return jdbc.sql(
            """
            INSERT INTO attachment (record_id, role, path, created_at)
            VALUES (:rid, :role, :path, now())
            RETURNING id
            """)
        .param("rid", recordId)
        .param("role", role)
        .param("path", path)
        .query(Long.class)
        .single();
  }

  private Long createPage(Long recordId, int seq, Long attachmentId) {
    return jdbc.sql(
            """
            INSERT INTO page (record_id, seq, attachment_id)
            VALUES (:rid, :seq, :aid)
            RETURNING id
            """)
        .param("rid", recordId)
        .param("seq", seq)
        .param("aid", attachmentId)
        .query(Long.class)
        .single();
  }

  private Long createJob(Long recordId, Long pageId, String kind, String status) {
    return jdbc.sql(
            """
            INSERT INTO job (record_id, page_id, kind, status, attempts, created_at)
            VALUES (:rid, :pid, :kind, :status, 0, now())
            RETURNING id
            """)
        .param("rid", recordId)
        .param("pid", pageId)
        .param("kind", kind)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  private void createPageText(Long pageId, String engine, String textRaw) {
    jdbc.sql(
            """
            INSERT INTO page_text (page_id, engine, text_raw, created_at)
            VALUES (:pid, :engine, :text, now())
            """)
        .param("pid", pageId)
        .param("engine", engine)
        .param("text", textRaw)
        .update();
  }

  private String getJobStatus(Long jobId) {
    return jdbc.sql("SELECT status FROM job WHERE id = :id")
        .param("id", jobId)
        .query(String.class)
        .single();
  }

  private void createTestImageFile(String relativePath) {
    try {
      BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(img, "jpg", baos);
      Path fullPath = Path.of(storageRoot).resolve(relativePath);
      Files.createDirectories(fullPath.getParent());
      Files.write(fullPath, baos.toByteArray());
    } catch (Exception e) {
      throw new RuntimeException("Failed to create test image", e);
    }
  }
}
