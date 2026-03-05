package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class PipelineStateMachineTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @Autowired private PipelineStateMachine stateMachine;
  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM pipeline_event").update();
    jdbc.sql("DELETE FROM text_chunk").update();
    jdbc.sql("DELETE FROM job").update();
    jdbc.sql("DELETE FROM page_text").update();
    jdbc.sql("DELETE FROM page_person_match").update();
    jdbc.sql("DELETE FROM page").update();
    jdbc.sql("UPDATE record SET pdf_attachment_id = NULL").update();
    jdbc.sql("DELETE FROM attachment").update();
    jdbc.sql("DELETE FROM record").update();
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

  private Long createPage(Long recordId, int seq) {
    Long attId = createAttachment(recordId, "page_image");
    return jdbc.sql(
            """
            INSERT INTO page (record_id, seq, attachment_id)
            VALUES (:rid, :seq, :aid)
            RETURNING id
            """)
        .param("rid", recordId)
        .param("seq", seq)
        .param("aid", attId)
        .query(Long.class)
        .single();
  }

  private Long createAttachment(Long recordId, String role) {
    return jdbc.sql(
            """
            INSERT INTO attachment (record_id, role, path, created_at)
            VALUES (:rid, :role, :path, now())
            RETURNING id
            """)
        .param("rid", recordId)
        .param("role", role)
        .param("path", "/test/" + recordId + "/" + role)
        .query(Long.class)
        .single();
  }

  private Long createJob(Long recordId, Long pageId, String kind, String status) {
    return jdbc.sql(
            """
            INSERT INTO job (record_id, page_id, kind, status, attempts, created_at, finished_at)
            VALUES (:rid, :pid, :kind, :status, 0, now(),
                    CASE WHEN :status = 'completed' THEN now() ELSE NULL END)
            RETURNING id
            """)
        .param("rid", recordId)
        .param("pid", pageId)
        .param("kind", kind)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  private void createPageText(Long pageId) {
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, text_raw, created_at) VALUES (:pid, 'test', 'text', now())")
        .param("pid", pageId)
        .update();
  }

  private String getRecordStatus(Long recordId) {
    return jdbc.sql("SELECT status FROM record WHERE id = :id")
        .param("id", recordId)
        .query(String.class)
        .single();
  }

  private long countJobs(Long recordId, String kind, String status) {
    return jdbc.sql(
            "SELECT count(*) FROM job WHERE record_id = :rid AND kind = :kind AND status = :status")
        .param("rid", recordId)
        .param("kind", kind)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  private long countPipelineEvents(Long recordId, String stage, String event) {
    return jdbc.sql(
            "SELECT count(*) FROM pipeline_event WHERE record_id = :rid AND stage = :stage AND event = :event")
        .param("rid", recordId)
        .param("stage", stage)
        .param("event", event)
        .query(Long.class)
        .single();
  }

  // ---------------------------------------------------------------------------
  // OCR_PENDING → OCR_DONE (all OCR jobs complete)
  // ---------------------------------------------------------------------------

  @Test
  void ocrPendingToOcrDone_whenAllJobsComplete() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 2);
    Long page1 = createPage(recordId, 1);
    Long page2 = createPage(recordId, 2);

    // Both OCR jobs completed
    createJob(recordId, page1, "ocr_page_paddle", "completed");
    createJob(recordId, page2, "ocr_page_paddle", "completed");

    boolean advanced = stateMachine.autoAdvance(recordId);

    // Should chain: ocr_pending → ocr_done → pdf_pending (has pages)
    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
    assertThat(countJobs(recordId, "build_searchable_pdf", "pending")).isEqualTo(1);
  }

  @Test
  void ocrPendingStays_whenJobsPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 2);
    Long page1 = createPage(recordId, 1);
    Long page2 = createPage(recordId, 2);

    createJob(recordId, page1, "ocr_page_paddle", "completed");
    createJob(recordId, page2, "ocr_page_paddle", "pending"); // still pending

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isFalse();
    assertThat(getRecordStatus(recordId)).isEqualTo("ocr_pending");
  }

  // ---------------------------------------------------------------------------
  // OCR_DONE → PDF_PENDING (has pages) / TRANSLATING (no pages) / EMBEDDING
  // ---------------------------------------------------------------------------

  @Test
  void ocrDoneToPdfPending_whenHasPages() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_done", 1);
    createPage(recordId, 1);

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
    assertThat(countJobs(recordId, "build_searchable_pdf", "pending")).isEqualTo(1);
    assertThat(countJobs(recordId, "translate_record", "pending")).isEqualTo(1);
    assertThat(countJobs(recordId, "translate_page", "pending")).isEqualTo(1);
  }

  @Test
  void ocrDoneToTranslating_whenNoPages_needsTranslation() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_done", 0);
    // metadata_lang is null → needs translation

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("translating");
    assertThat(countJobs(recordId, "translate_record", "pending")).isEqualTo(1);
  }

  @Test
  void ocrDoneToEmbedding_whenNoPages_englishMetadata() {
    Long archiveId = createArchive();
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, metadata_lang, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ocr_done', 0, 0, 'en', now(), now())
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "REC-" + System.nanoTime())
            .query(Long.class)
            .single();

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("embedding");
    assertThat(countJobs(recordId, "embed_record", "pending")).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // PDF_PENDING → PDF_DONE → TRANSLATING/EMBEDDING chain
  // ---------------------------------------------------------------------------

  @Test
  void pdfPendingToPdfDone_thenTranslating() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    Long page1 = createPage(recordId, 1);

    // PDF job completed, attachment exists
    createJob(recordId, null, "build_searchable_pdf", "completed");
    createAttachment(recordId, "searchable_pdf");
    // Translation still pending
    createJob(recordId, page1, "translate_page", "pending");

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    // Should chain: pdf_pending → pdf_done → translating
    assertThat(getRecordStatus(recordId)).isEqualTo("translating");
  }

  @Test
  void pdfPendingToPdfDone_thenEmbedding_noTranslation() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    createPage(recordId, 1);

    // PDF job completed, attachment exists
    createJob(recordId, null, "build_searchable_pdf", "completed");
    createAttachment(recordId, "searchable_pdf");
    // No translation jobs

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    // Should chain: pdf_pending → pdf_done → embedding
    assertThat(getRecordStatus(recordId)).isEqualTo("embedding");
    assertThat(countJobs(recordId, "embed_record", "pending")).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // TRANSLATING → EMBEDDING
  // ---------------------------------------------------------------------------

  @Test
  void translatingToEmbedding_whenAllTranslationDone() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "translating", 1);
    Long page1 = createPage(recordId, 1);

    createJob(recordId, page1, "translate_page", "completed");
    createJob(recordId, null, "translate_record", "completed");

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("embedding");
    assertThat(countJobs(recordId, "embed_record", "pending")).isEqualTo(1);
    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(1);
  }

  @Test
  void translatingStays_whenTranslationPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "translating", 1);
    Long page1 = createPage(recordId, 1);

    createJob(recordId, page1, "translate_page", "claimed"); // still in progress

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isFalse();
    assertThat(getRecordStatus(recordId)).isEqualTo("translating");
  }

  // ---------------------------------------------------------------------------
  // EMBEDDING → MATCHING
  // ---------------------------------------------------------------------------

  @Test
  void embeddingToMatching_whenEmbedComplete() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "embedding", 1);
    createPage(recordId, 1);

    createJob(recordId, null, "embed_record", "completed");

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("matching");
    assertThat(countJobs(recordId, "match_persons", "pending")).isEqualTo(1);
    assertThat(countPipelineEvents(recordId, "embedding", "completed")).isEqualTo(1);
    assertThat(countPipelineEvents(recordId, "matching", "started")).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // MATCHING → COMPLETE
  // ---------------------------------------------------------------------------

  @Test
  void matchingToComplete_whenMatchDone() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "matching", 1);
    createPage(recordId, 1);

    createJob(recordId, null, "match_persons", "completed");

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("complete");
    assertThat(countPipelineEvents(recordId, "matching", "completed")).isEqualTo(1);
  }

  @Test
  void matchingStays_whenMatchPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "matching", 1);
    createPage(recordId, 1);

    createJob(recordId, null, "match_persons", "pending");

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isFalse();
    assertThat(getRecordStatus(recordId)).isEqualTo("matching");
  }

  // ---------------------------------------------------------------------------
  // Full chain: OCR_PENDING → ... → MATCHING (stops because match_persons is pending)
  // ---------------------------------------------------------------------------

  @Test
  void fullChain_ocrPendingToMatching_noPages() {
    Long archiveId = createArchive();
    // 0-page record in ocr_pending with metadata_lang='en' (no translation needed)
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, metadata_lang, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ocr_pending', 0, 0, 'en', now(), now())
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "REC-" + System.nanoTime())
            .query(Long.class)
            .single();

    boolean advanced = stateMachine.autoAdvance(recordId);

    // Should chain: ocr_pending → ocr_done → embedding (no pages, English metadata)
    // Stops at embedding because embed_record job is pending (not completed yet)
    assertThat(advanced).isTrue();
    assertThat(getRecordStatus(recordId)).isEqualTo("embedding");
    assertThat(countJobs(recordId, "embed_record", "pending")).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // COMPLETE stays — no transitions from COMPLETE
  // ---------------------------------------------------------------------------

  @Test
  void completeStays_noTransitions() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "complete", 0);

    boolean advanced = stateMachine.autoAdvance(recordId);

    assertThat(advanced).isFalse();
    assertThat(getRecordStatus(recordId)).isEqualTo("complete");
  }
}
