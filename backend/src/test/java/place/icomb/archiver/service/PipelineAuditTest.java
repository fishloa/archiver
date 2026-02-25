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
class PipelineAuditTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @Autowired private JobService jobService;
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
    jdbc.sql("DELETE FROM job").update();
    jdbc.sql("DELETE FROM page_text").update();
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
    return jdbc
        .sql(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                attachment_count, created_at, updated_at)
            VALUES (:aid, 'test', :srcId, :status, :pc, 0, now() - interval '1 hour', now() - interval '30 minutes')
            RETURNING id
            """)
        .param("aid", archiveId)
        .param("srcId", "REC-" + System.nanoTime())
        .param("status", status)
        .param("pc", pageCount)
        .query(Long.class)
        .single();
  }

  private Long createAttachment(Long recordId, String role) {
    return jdbc
        .sql(
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

  private Long createPage(Long recordId, int seq) {
    Long attachmentId = createAttachment(recordId, "page_image");
    return jdbc
        .sql(
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

  private Long createJob(Long recordId, Long pageId, String kind, String status, int attempts) {
    return jdbc
        .sql(
            """
            INSERT INTO job (record_id, page_id, kind, status, attempts, created_at)
            VALUES (:rid, :pid, :kind, :status, :attempts, now())
            RETURNING id
            """)
        .param("rid", recordId)
        .param("pid", pageId)
        .param("kind", kind)
        .param("status", status)
        .param("attempts", attempts)
        .query(Long.class)
        .single();
  }

  private Long createJobWithStartedAt(
      Long recordId, Long pageId, String kind, String status, int attempts, String startedAtExpr) {
    return jdbc
        .sql(
            """
            INSERT INTO job (record_id, page_id, kind, status, attempts, created_at, started_at)
            VALUES (:rid, :pid, :kind, :status, :attempts, now(), %s)
            RETURNING id
            """.formatted(startedAtExpr))
        .param("rid", recordId)
        .param("pid", pageId)
        .param("kind", kind)
        .param("status", status)
        .param("attempts", attempts)
        .query(Long.class)
        .single();
  }

  private Long createJobWithError(
      Long recordId, Long pageId, String kind, String status, int attempts, String error) {
    return jdbc
        .sql(
            """
            INSERT INTO job (record_id, page_id, kind, status, attempts, error, created_at, finished_at)
            VALUES (:rid, :pid, :kind, :status, :attempts, :error, now(), now())
            RETURNING id
            """)
        .param("rid", recordId)
        .param("pid", pageId)
        .param("kind", kind)
        .param("status", status)
        .param("attempts", attempts)
        .param("error", error)
        .query(Long.class)
        .single();
  }

  private String getRecordStatus(Long recordId) {
    return jdbc
        .sql("SELECT status FROM record WHERE id = :id")
        .param("id", recordId)
        .query(String.class)
        .single();
  }

  private String getJobStatus(Long jobId) {
    return jdbc
        .sql("SELECT status FROM job WHERE id = :id")
        .param("id", jobId)
        .query(String.class)
        .single();
  }

  private long countJobs(Long recordId, String kind, String status) {
    return jdbc
        .sql(
            "SELECT count(*) FROM job WHERE record_id = :rid AND kind = :kind AND status = :status")
        .param("rid", recordId)
        .param("kind", kind)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  private long countPipelineEvents(Long recordId, String stage, String event) {
    return jdbc
        .sql(
            """
            SELECT count(*) FROM pipeline_event
            WHERE record_id = :rid AND stage = :stage AND event = :event
            """)
        .param("rid", recordId)
        .param("stage", stage)
        .param("event", event)
        .query(Long.class)
        .single();
  }

  // ---------------------------------------------------------------------------
  // Pass 1 — Stale claimed jobs
  // ---------------------------------------------------------------------------

  @Test
  void pass1_staleClaimed_resetsJobToPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 1);
    Long pageId = createPage(recordId, 1);

    // Insert a job that was claimed over 1 hour ago
    Long jobId =
        createJobWithStartedAt(
            recordId, pageId, "ocr_page_paddle", "claimed", 1, "now() - interval '2 hours'");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getJobStatus(jobId)).isEqualTo("pending");

    // Verify started_at was cleared
    String startedAt =
        jdbc.sql("SELECT started_at::text FROM job WHERE id = :id")
            .param("id", jobId)
            .query(String.class)
            .optional()
            .orElse(null);
    assertThat(startedAt).isNull();
  }

  @Test
  void pass1_recentClaimed_notReset() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 1);
    Long pageId = createPage(recordId, 1);

    // A job claimed only 30 minutes ago — should NOT be reset
    Long jobId =
        createJobWithStartedAt(
            recordId, pageId, "ocr_page_paddle", "claimed", 1, "now() - interval '30 minutes'");

    jobService.auditPipeline();

    assertThat(getJobStatus(jobId)).isEqualTo("claimed");
  }

  @Test
  void pass1_multipleStaleClaimed_allReset() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 2);
    Long pageId1 = createPage(recordId, 1);
    Long pageId2 = createPage(recordId, 2);

    Long jobId1 =
        createJobWithStartedAt(
            recordId, pageId1, "ocr_page_paddle", "claimed", 1, "now() - interval '90 minutes'");
    Long jobId2 =
        createJobWithStartedAt(
            recordId, pageId2, "ocr_page_paddle", "claimed", 2, "now() - interval '3 hours'");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(2);
    assertThat(getJobStatus(jobId1)).isEqualTo("pending");
    assertThat(getJobStatus(jobId2)).isEqualTo("pending");
  }

  // ---------------------------------------------------------------------------
  // Pass 2 — Failed job retry
  // ---------------------------------------------------------------------------

  @Test
  void pass2_failedJobUnderThreeAttempts_resetToPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 1);
    Long pageId = createPage(recordId, 1);

    Long jobId =
        createJobWithError(
            recordId, pageId, "ocr_page_paddle", "failed", 2, "Timeout during OCR processing");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getJobStatus(jobId)).isEqualTo("pending");

    // Error should be cleared
    String error =
        jdbc.sql("SELECT error FROM job WHERE id = :id")
            .param("id", jobId)
            .query(String.class)
            .optional()
            .orElse(null);
    assertThat(error).isNull();
  }

  @Test
  void pass2_failedJobAtThreeAttempts_notRetried() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 1);
    Long pageId = createPage(recordId, 1);

    // attempts = 3 → should NOT be reset (condition is attempts < 3)
    Long jobId =
        createJobWithError(
            recordId, pageId, "ocr_page_paddle", "failed", 3, "Persistent OCR failure");

    jobService.auditPipeline();

    assertThat(getJobStatus(jobId)).isEqualTo("failed");
  }

  @Test
  void pass2_failedJobWithZeroAttempts_resetToPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_pending", 1);
    Long pageId = createPage(recordId, 1);

    Long jobId =
        createJobWithError(recordId, pageId, "ocr_page_paddle", "failed", 0, "Initial failure");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getJobStatus(jobId)).isEqualTo("pending");
  }

  @Test
  void pass2_failedPdfJob_resetToPending() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);

    Long jobId =
        createJobWithError(
            recordId, null, "build_searchable_pdf", "failed", 1, "PDF build failed");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getJobStatus(jobId)).isEqualTo("pending");
  }

  // ---------------------------------------------------------------------------
  // Pass 3 — Stuck ingesting records
  // ---------------------------------------------------------------------------

  @Test
  void pass3_stuckIngesting_allPagesPresent_transitionsToOcrPending() {
    Long archiveId = createArchive();

    // Create a record stuck in 'ingesting' with updated_at > 10 minutes ago
    Long recordId =
        jdbc
            .sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ingesting', 2, 0,
                    now() - interval '30 minutes', now() - interval '15 minutes')
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "STUCK-" + System.nanoTime())
            .query(Long.class)
            .single();

    // Insert exactly page_count pages
    createPage(recordId, 1);
    createPage(recordId, 2);

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getRecordStatus(recordId)).isEqualTo("ocr_pending");

    // Should have enqueued 2 OCR jobs
    assertThat(countJobs(recordId, "ocr_page_paddle", "pending")).isEqualTo(2);

    // Should have pipeline events: ingest completed + ocr started
    assertThat(countPipelineEvents(recordId, "ingest", "completed")).isEqualTo(1);
    assertThat(countPipelineEvents(recordId, "ocr", "started")).isEqualTo(1);
  }

  @Test
  void pass3_stuckIngesting_missingPages_notTransitioned() {
    Long archiveId = createArchive();

    // page_count = 3 but only 1 page uploaded
    Long recordId =
        jdbc
            .sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ingesting', 3, 0,
                    now() - interval '30 minutes', now() - interval '15 minutes')
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "PARTIAL-" + System.nanoTime())
            .query(Long.class)
            .single();

    createPage(recordId, 1);

    jobService.auditPipeline();

    // Still ingesting — pages are incomplete
    assertThat(getRecordStatus(recordId)).isEqualTo("ingesting");
    assertThat(countJobs(recordId, "ocr_page_paddle", "pending")).isEqualTo(0);
  }

  @Test
  void pass3_stuckIngesting_recentUpdate_notTransitioned() {
    Long archiveId = createArchive();

    // updated_at only 5 minutes ago — still within the 10-minute threshold
    Long recordId =
        jdbc
            .sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ingesting', 1, 0,
                    now() - interval '10 minutes', now() - interval '5 minutes')
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "RECENT-" + System.nanoTime())
            .query(Long.class)
            .single();

    createPage(recordId, 1);

    jobService.auditPipeline();

    assertThat(getRecordStatus(recordId)).isEqualTo("ingesting");
    assertThat(countJobs(recordId, "ocr_page_paddle", "pending")).isEqualTo(0);
  }

  @Test
  void pass3_stuckIngesting_zeroPageCount_notTransitioned() {
    Long archiveId = createArchive();

    // page_count = 0 — no pages expected, condition page_count > 0 excludes this
    Long recordId =
        jdbc
            .sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ingesting', 0, 0,
                    now() - interval '30 minutes', now() - interval '15 minutes')
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "ZERO-" + System.nanoTime())
            .query(Long.class)
            .single();

    jobService.auditPipeline();

    assertThat(getRecordStatus(recordId)).isEqualTo("ingesting");
  }

  // ---------------------------------------------------------------------------
  // Pass 4 — ocr_done without post-OCR jobs
  // ---------------------------------------------------------------------------

  @Test
  void pass4_ocrDoneNoPdfJob_startsPipeline() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_done", 2);
    Long pageId1 = createPage(recordId, 1);
    Long pageId2 = createPage(recordId, 2);

    // No build_searchable_pdf job exists yet

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);

    // Record should transition to pdf_pending
    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");

    // Should have enqueued a PDF build job
    assertThat(countJobs(recordId, "build_searchable_pdf", "pending")).isEqualTo(1);

    // Should have enqueued a translate_record job
    assertThat(countJobs(recordId, "translate_record", "pending")).isEqualTo(1);

    // Should have enqueued translate_page jobs for each page (lang is null → not English)
    assertThat(countJobs(recordId, "translate_page", "pending")).isEqualTo(2);

    // Pipeline events: pdf_build started + translation started
    assertThat(countPipelineEvents(recordId, "pdf_build", "started")).isEqualTo(1);
    assertThat(countPipelineEvents(recordId, "translation", "started")).isEqualTo(1);
  }

  @Test
  void pass4_ocrDoneWithPdfJob_notRetriggered() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "ocr_done", 1);
    createPage(recordId, 1);

    // A PDF job already exists — should not be re-triggered
    createJob(recordId, null, "build_searchable_pdf", "pending", 0);

    int fixedBefore =
        jdbc.sql("SELECT count(*) FROM job WHERE record_id = :rid AND kind = 'build_searchable_pdf'")
            .param("rid", recordId)
            .query(Long.class)
            .single()
            .intValue();

    jobService.auditPipeline();

    // Still ocr_done (startPostOcrPipeline was not called for this record)
    assertThat(getRecordStatus(recordId)).isEqualTo("ocr_done");

    long pdfJobCount =
        jdbc.sql("SELECT count(*) FROM job WHERE record_id = :rid AND kind = 'build_searchable_pdf'")
            .param("rid", recordId)
            .query(Long.class)
            .single();
    assertThat(pdfJobCount).isEqualTo(fixedBefore); // no new PDF job added
  }

  @Test
  void pass4_ocrDoneEnglishRecord_skipsPageTranslation() {
    Long archiveId = createArchive();

    // Record with lang = 'en' — page translation should be skipped
    Long recordId =
        jdbc
            .sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, status, page_count,
                    attachment_count, lang, created_at, updated_at)
                VALUES (:aid, 'test', :srcId, 'ocr_done', 1, 0, 'en',
                    now() - interval '1 hour', now() - interval '30 minutes')
                RETURNING id
                """)
            .param("aid", archiveId)
            .param("srcId", "EN-" + System.nanoTime())
            .query(Long.class)
            .single();
    createPage(recordId, 1);

    jobService.auditPipeline();

    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
    assertThat(countJobs(recordId, "build_searchable_pdf", "pending")).isEqualTo(1);
    // No page translation for English content
    assertThat(countJobs(recordId, "translate_page", "pending")).isEqualTo(0);
    // translate_record still runs (metadata may be in another language)
    assertThat(countJobs(recordId, "translate_record", "pending")).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Pass 5 — pdf_pending with completed PDF job
  // ---------------------------------------------------------------------------

  @Test
  void pass5_pdfPendingWithCompletedJob_transitionsToPdfDone() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    createPage(recordId, 1);

    // PDF job is completed
    Long pdfJobId = createJob(recordId, null, "build_searchable_pdf", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id").param("id", pdfJobId).update();

    // searchable_pdf attachment exists
    Long attId = createAttachment(recordId, "searchable_pdf");

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_done");

    // pdf_attachment_id should be set on the record
    Long pdfAttId =
        jdbc.sql("SELECT pdf_attachment_id FROM record WHERE id = :id")
            .param("id", recordId)
            .query(Long.class)
            .single();
    assertThat(pdfAttId).isEqualTo(attId);

    // pdf_build completed pipeline event should exist
    assertThat(countPipelineEvents(recordId, "pdf_build", "completed")).isEqualTo(1);
  }

  @Test
  void pass5_pdfPendingNoAttachment_notTransitioned() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    createPage(recordId, 1);

    // PDF job is completed but no attachment uploaded yet
    Long pdfJobId = createJob(recordId, null, "build_searchable_pdf", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id").param("id", pdfJobId).update();

    jobService.auditPipeline();

    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
  }

  @Test
  void pass5_pdfPendingJobNotCompleted_notTransitioned() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    createPage(recordId, 1);

    // PDF job still in progress
    createJob(recordId, null, "build_searchable_pdf", "claimed", 1);
    createAttachment(recordId, "searchable_pdf");

    jobService.auditPipeline();

    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
  }

  @Test
  void pass5_pdfPendingNoJob_notTransitioned() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_pending", 1);
    createPage(recordId, 1);

    // No PDF job at all
    createAttachment(recordId, "searchable_pdf");

    jobService.auditPipeline();

    // No completed PDF job → Pass 5 doesn't match, record stays pdf_pending
    assertThat(getRecordStatus(recordId)).isEqualTo("pdf_pending");
  }

  // ---------------------------------------------------------------------------
  // Pass 6 — Missing translation completed events
  // ---------------------------------------------------------------------------

  @Test
  void pass6_pdfDoneAllTranslationsCompleted_backfillsEvent() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_done", 1);
    Long pageId = createPage(recordId, 1);

    // Both translate jobs completed
    Long translatePageJob = createJob(recordId, pageId, "translate_page", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id")
        .param("id", translatePageJob)
        .update();
    Long translateRecordJob = createJob(recordId, null, "translate_record", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id")
        .param("id", translateRecordJob)
        .update();

    // No translation/completed pipeline_event exists yet

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(1);
  }

  @Test
  void pass6_pdfDoneAllTranslationsFailed_backfillsEvent() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_done", 1);
    Long pageId = createPage(recordId, 1);

    // All translate jobs failed (still counts as "done" for audit purposes)
    Long translatePageJob =
        createJobWithError(recordId, pageId, "translate_page", "failed", 3, "Translation error");
    Long translateRecordJob =
        createJobWithError(recordId, null, "translate_record", "failed", 3, "Translation error");

    // No translation/completed event

    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(1);
    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(1);
  }

  @Test
  void pass6_pdfDoneEventAlreadyExists_notDuplicated() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_done", 1);
    Long pageId = createPage(recordId, 1);

    Long translateJob = createJob(recordId, pageId, "translate_page", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id").param("id", translateJob).update();

    // Manually insert the completed event — audit should not add a duplicate
    jdbc.sql(
            "INSERT INTO pipeline_event (record_id, stage, event, created_at) VALUES (:rid, 'translation', 'completed', now())")
        .param("rid", recordId)
        .update();

    jobService.auditPipeline();

    // Still exactly 1 event
    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(1);
  }

  @Test
  void pass6_pdfDoneTranslationStillPending_notBackfilled() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_done", 1);
    Long pageId = createPage(recordId, 1);

    // translate_page is still pending — not all jobs are done
    createJob(recordId, pageId, "translate_page", "pending", 0);
    Long translateRecordJob = createJob(recordId, null, "translate_record", "completed", 1);
    jdbc.sql("UPDATE job SET finished_at = now() WHERE id = :id")
        .param("id", translateRecordJob)
        .update();

    jobService.auditPipeline();

    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(0);
  }

  @Test
  void pass6_pdfDoneNoTranslationJobs_notBackfilled() {
    Long archiveId = createArchive();
    Long recordId = createRecord(archiveId, "pdf_done", 1);
    createPage(recordId, 1);

    // No translation jobs at all — the EXISTS check for translate jobs fails → not matched
    jobService.auditPipeline();

    assertThat(countPipelineEvents(recordId, "translation", "completed")).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // Multi-pass — all passes run in a single audit call
  // ---------------------------------------------------------------------------

  @Test
  void audit_multiplePassesInSingleCall_allFixed() {
    Long archiveId = createArchive();

    // Pass 1: stale claimed job
    Long record1 = createRecord(archiveId, "ocr_pending", 1);
    Long page1 = createPage(record1, 1);
    Long staleJob =
        createJobWithStartedAt(
            record1, page1, "ocr_page_paddle", "claimed", 1, "now() - interval '2 hours'");

    // Pass 2: failed job with low attempts
    Long record2 = createRecord(archiveId, "ocr_pending", 1);
    Long page2 = createPage(record2, 1);
    Long failedJob =
        createJobWithError(record2, page2, "ocr_page_paddle", "failed", 1, "Transient error");

    // Pass 4: ocr_done with no PDF job
    Long record4 = createRecord(archiveId, "ocr_done", 1);
    createPage(record4, 1);

    // Run audit
    int fixed = jobService.auditPipeline();

    assertThat(fixed).isGreaterThanOrEqualTo(3);

    // Pass 1 result
    assertThat(getJobStatus(staleJob)).isEqualTo("pending");

    // Pass 2 result
    assertThat(getJobStatus(failedJob)).isEqualTo("pending");

    // Pass 4 result
    assertThat(getRecordStatus(record4)).isEqualTo("pdf_pending");
    assertThat(countJobs(record4, "build_searchable_pdf", "pending")).isEqualTo(1);
  }

  @Test
  void audit_noStuckRecordsOrJobs_returnsZero() {
    // Nothing to fix — audit should return 0
    int fixed = jobService.auditPipeline();
    assertThat(fixed).isEqualTo(0);
  }
}
