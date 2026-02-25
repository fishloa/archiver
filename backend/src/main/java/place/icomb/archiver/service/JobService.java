package place.icomb.archiver.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.repository.JobRepository;

@Service
public class JobService {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  private final JobRepository jobRepository;
  private final JdbcTemplate jdbcTemplate;
  private final JobEventService jobEventService;
  private final RecordEventService recordEventService;

  public JobService(
      JobRepository jobRepository,
      JdbcTemplate jdbcTemplate,
      JobEventService jobEventService,
      RecordEventService recordEventService) {
    this.jobRepository = jobRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.jobEventService = jobEventService;
    this.recordEventService = recordEventService;
  }

  /** Creates a new pending job and fires a NOTIFY on the appropriate channel. */
  @Transactional
  public Job enqueueJob(String kind, Long recordId, Long pageId, String payload) {
    Job job = new Job();
    job.setKind(kind);
    job.setRecordId(recordId);
    job.setPageId(pageId);
    job.setPayload(payload);
    job.setStatus("pending");
    job.setAttempts(0);
    job.setCreatedAt(Instant.now());
    job = jobRepository.save(job);

    // Notify connected workers via SSE
    jobEventService.jobEnqueued(kind);
    // Notify UI (pipeline dashboard)
    recordEventService.pipelineChanged(kind, "pending");

    return job;
  }

  /**
   * Atomically claims the next pending job of the given kind. Returns empty if no job is available.
   */
  @Transactional
  public Optional<Job> claimJob(String kind) {
    return jobRepository.findAndClaimNextJob(kind);
  }

  /** Marks a job as completed with an optional result payload. */
  @Transactional
  public Job completeJob(Long jobId, String result) {
    Job job =
        jobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus("completed");
    job.setPayload(result);
    job.setFinishedAt(Instant.now());
    job = jobRepository.save(job);
    recordEventService.pipelineChanged(job.getKind(), "completed");

    // Check if all OCR jobs for this record are now complete
    if (job.getRecordId() != null && isOcrKind(job.getKind())) {
      checkRecordOcrComplete(job.getRecordId());
    }

    // Check if PDF build is complete
    if (job.getRecordId() != null && "build_searchable_pdf".equals(job.getKind())) {
      checkRecordPdfComplete(job.getRecordId());
    }

    // Check if all translation jobs for this record are done
    if (job.getRecordId() != null
        && ("translate_page".equals(job.getKind()) || "translate_record".equals(job.getKind()))) {
      checkRecordTranslationComplete(job.getRecordId());
    }

    return job;
  }

  /**
   * If every page in the record has OCR text, transition the record status from ocr_pending to
   * ocr_done, then immediately start the PDF build + translation pipeline.
   */
  private void checkRecordOcrComplete(Long recordId) {
    Long pending =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM page p
            WHERE p.record_id = ?
              AND NOT EXISTS (SELECT 1 FROM page_text pt WHERE pt.page_id = p.id)
            """,
            Long.class,
            recordId);
    if (pending != null && pending == 0) {
      int updated =
          jdbcTemplate.update(
              "UPDATE record SET status = 'ocr_done', updated_at = now() WHERE id = ? AND status = 'ocr_pending'",
              recordId);
      if (updated > 0) {
        log.info("Record {} transitioned to ocr_done", recordId);
        logPipelineEvent(recordId, "ocr", "completed", null);
        recordEventService.recordChanged(recordId, "status");
        startPostOcrPipeline(recordId);
      }
    }
  }

  /** Enqueue PDF build and translation jobs, then transition to pdf_pending. */
  private void startPostOcrPipeline(Long recordId) {
    // Enqueue one build_searchable_pdf job for the whole record
    enqueueJob("build_searchable_pdf", recordId, null, null);

    // Get the record's language fields
    var langRow =
        jdbcTemplate.queryForMap("SELECT lang, metadata_lang FROM record WHERE id = ?", recordId);
    String contentLang = (String) langRow.get("lang");
    String metadataLang = (String) langRow.get("metadata_lang");

    // Enqueue metadata translation with metadata_lang (hardcoded per scraper)
    String metaPayload = metadataLang != null ? "{\"lang\":\"" + metadataLang + "\"}" : null;
    enqueueJob("translate_record", recordId, null, metaPayload);

    // Enqueue OCR text translation (auto-detect language from text)
    // Skip if content is explicitly marked as English
    int translateCount = 0;
    if (contentLang == null || !"en".equals(contentLang)) {
      List<Long> pageIds =
          jdbcTemplate.queryForList(
              "SELECT p.id FROM page p WHERE p.record_id = ? ORDER BY p.seq", Long.class, recordId);
      for (Long pageId : pageIds) {
        // No lang in payload → translator auto-detects from text content
        enqueueJob("translate_page", recordId, pageId, null);
      }
      translateCount = pageIds.size();
    } else {
      log.info("Record {} content is English (lang=en), skipping page translation", recordId);
    }

    // Transition to pdf_pending
    jdbcTemplate.update(
        "UPDATE record SET status = 'pdf_pending', updated_at = now() WHERE id = ? AND status = 'ocr_done'",
        recordId);
    log.info(
        "Record {} → pdf_pending ({} translate jobs + 1 pdf job enqueued)",
        recordId,
        translateCount);
    logPipelineEvent(recordId, "pdf_build", "started", null);
    logPipelineEvent(recordId, "translation", "started", translateCount + " page jobs enqueued");
    recordEventService.recordChanged(recordId, "status");
  }

  private static boolean isOcrKind(String kind) {
    return kind != null && kind.startsWith("ocr_page_");
  }

  /**
   * Comprehensive pipeline audit that detects and fixes stuck records and jobs.
   *
   * @return total number of records/jobs fixed
   */
  @Transactional
  public int auditPipeline() {
    int total = 0;

    // --- Pass 1: Reset stale claimed jobs (claimed > 1 hour ago) back to pending ---
    int staleClaimed =
        jdbcTemplate.update(
            """
            UPDATE job SET status = 'pending', started_at = NULL, attempts = attempts
            WHERE status = 'claimed'
              AND started_at < now() - interval '1 hour'
            """);
    if (staleClaimed > 0) {
      log.info("Audit: reset {} stale claimed jobs to pending", staleClaimed);
    }
    total += staleClaimed;

    // --- Pass 2: Retry failed jobs (reset to pending, clear error) ---
    int failedRetried =
        jdbcTemplate.update(
            """
            UPDATE job SET status = 'pending', error = NULL, finished_at = NULL
            WHERE status = 'failed'
              AND attempts < 3
            """);
    if (failedRetried > 0) {
      log.info("Audit: retried {} failed jobs (attempts < 3)", failedRetried);
    }
    total += failedRetried;

    // --- Pass 3: Stuck ingesting records where all pages are present ---
    //     The scraper uploaded all pages but never called completeIngest.
    //     Transition to ocr_pending and enqueue OCR jobs.
    List<Long> ingestingStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ingesting'
              AND r.page_count > 0
              AND r.page_count = (SELECT count(*) FROM page p WHERE p.record_id = r.id)
              AND r.updated_at < now() - interval '10 minutes'
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ingestingStuck) {
      log.info("Audit: completing stuck ingest for record {}", recordId);
      // Replicate IngestService.completeIngest logic
      var langRow = jdbcTemplate.queryForMap("SELECT lang FROM record WHERE id = ?", recordId);
      String lang = (String) langRow.get("lang");
      String ocrPayload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;

      List<Long> pageIds =
          jdbcTemplate.queryForList(
              "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recordId);
      for (Long pageId : pageIds) {
        enqueueJob("ocr_page_paddle", recordId, pageId, ocrPayload);
      }

      jdbcTemplate.update(
          "UPDATE record SET status = 'ocr_pending', updated_at = now() WHERE id = ?", recordId);
      logPipelineEvent(recordId, "ingest", "completed", "from audit: " + pageIds.size() + " pages");
      logPipelineEvent(
          recordId, "ocr", "started", "from audit: " + pageIds.size() + " jobs enqueued");
      recordEventService.recordChanged(recordId, "status");
    }
    total += ingestingStuck.size();

    // --- Pass 4: ocr_done records with no build_searchable_pdf job ---
    List<Long> ocrDoneStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ocr_done'
              AND NOT EXISTS (
                SELECT 1 FROM job j
                WHERE j.record_id = r.id
                  AND j.kind = 'build_searchable_pdf'
              )
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ocrDoneStuck) {
      log.info("Audit: re-queuing post-OCR pipeline for stuck record {}", recordId);
      startPostOcrPipeline(recordId);
    }
    total += ocrDoneStuck.size();

    // --- Pass 5: pdf_pending records whose build_searchable_pdf job is completed
    //             but the record never transitioned to pdf_done ---
    List<Long> pdfPendingStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'pdf_pending'
              AND EXISTS (
                SELECT 1 FROM job j
                WHERE j.record_id = r.id
                  AND j.kind = 'build_searchable_pdf'
                  AND j.status = 'completed'
              )
              AND EXISTS (
                SELECT 1 FROM attachment a
                WHERE a.record_id = r.id
                  AND a.role = 'searchable_pdf'
              )
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : pdfPendingStuck) {
      log.info("Audit: nudging pdf_pending → pdf_done for record {}", recordId);
      checkRecordPdfComplete(recordId);
    }
    total += pdfPendingStuck.size();

    // --- Pass 6: Migrate pdf_done records to translating or complete ---
    //     Records stuck in pdf_done from before the translating status was added.
    List<Long> pdfDoneStuck =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'pdf_done'
        ORDER BY r.id
        """,
            Long.class);

    int pdfDoneToTranslating = 0;
    int pdfDoneToComplete = 0;
    for (Long recordId : pdfDoneStuck) {
      Long pendingTranslation =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM job WHERE record_id = ? AND kind IN ('translate_page', 'translate_record') AND status != 'completed'",
              Long.class,
              recordId);
      if (pendingTranslation != null && pendingTranslation > 0) {
        jdbcTemplate.update(
            "UPDATE record SET status = 'translating', updated_at = now() WHERE id = ?", recordId);
        log.info(
            "Audit: record {} pdf_done → translating ({} jobs remaining)",
            recordId,
            pendingTranslation);
        pdfDoneToTranslating++;
      } else {
        jdbcTemplate.update(
            "UPDATE record SET status = 'complete', updated_at = now() WHERE id = ?", recordId);
        log.info("Audit: record {} pdf_done → complete (translation done)", recordId);
        pdfDoneToComplete++;
      }
      recordEventService.recordChanged(recordId, "status");
    }
    total += pdfDoneStuck.size();

    // --- Pass 7: Stuck translating records where all translation jobs are actually done ---
    List<Long> translatingDone =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'translating'
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind IN ('translate_page', 'translate_record')
              AND j.status NOT IN ('completed', 'failed')
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : translatingDone) {
      jdbcTemplate.update(
          "UPDATE record SET status = 'complete', updated_at = now() WHERE id = ?", recordId);
      log.info("Audit: record {} translating → complete (all translation done)", recordId);
      logPipelineEvent(recordId, "translation", "completed", "from audit");
      recordEventService.recordChanged(recordId, "status");
    }
    total += translatingDone.size();

    // --- Pass 8: Backfill missing translation completed events ---
    List<Long> translationEventsMissing =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'complete'
          AND NOT EXISTS (
            SELECT 1 FROM pipeline_event pe
            WHERE pe.record_id = r.id AND pe.stage = 'translation' AND pe.event = 'completed'
          )
          AND EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind IN ('translate_page', 'translate_record')
              AND j.status = 'completed'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : translationEventsMissing) {
      log.info("Audit: logging missing translation completed event for record {}", recordId);
      logPipelineEvent(recordId, "translation", "completed", "retroactive from audit");
    }
    total += translationEventsMissing.size();

    log.info(
        "Pipeline audit complete: {} stale jobs reset, {} failed retried, {} ingesting fixed, "
            + "{} ocr_done re-queued, {} pdf_pending nudged, {} pdf_done→translating, {} pdf_done→complete, "
            + "{} translating→complete, {} translation events backfilled ({} total)",
        staleClaimed,
        failedRetried,
        ingestingStuck.size(),
        ocrDoneStuck.size(),
        pdfPendingStuck.size(),
        pdfDoneToTranslating,
        pdfDoneToComplete,
        translatingDone.size(),
        translationEventsMissing.size(),
        total);
    return total;
  }

  /** Marks a job as failed with an error message. */
  @Transactional
  public Job failJob(Long jobId, String error) {
    Job job =
        jobRepository
            .findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    job.setStatus("failed");
    job.setError(error);
    job.setFinishedAt(Instant.now());
    job = jobRepository.save(job);
    recordEventService.pipelineChanged(job.getKind(), "failed");
    return job;
  }

  /**
   * After a build_searchable_pdf job completes, set the record's pdf_attachment_id and transition
   * to pdf_done.
   */
  private void checkRecordPdfComplete(Long recordId) {
    // Find the searchable_pdf attachment
    Long pdfAttId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM attachment WHERE record_id = ? AND role = 'searchable_pdf' ORDER BY id DESC LIMIT 1",
            Long.class,
            recordId);
    if (pdfAttId != null) {
      int updated =
          jdbcTemplate.update(
              "UPDATE record SET pdf_attachment_id = ?, status = 'pdf_done', updated_at = now() WHERE id = ? AND status = 'pdf_pending'",
              pdfAttId,
              recordId);
      if (updated > 0) {
        log.info("Record {} → pdf_done (pdf_attachment_id={})", recordId, pdfAttId);
        logPipelineEvent(recordId, "pdf_build", "completed", "attachment_id=" + pdfAttId);
        // Check if translation is still running → move to 'translating', otherwise 'complete'
        Long pendingTranslation =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job WHERE record_id = ? AND kind IN ('translate_page', 'translate_record') AND status != 'completed'",
                Long.class,
                recordId);
        if (pendingTranslation != null && pendingTranslation > 0) {
          jdbcTemplate.update(
              "UPDATE record SET status = 'translating', updated_at = now() WHERE id = ? AND status = 'pdf_done'",
              recordId);
          log.info(
              "Record {} → translating ({} translation jobs remaining)",
              recordId,
              pendingTranslation);
        } else {
          jdbcTemplate.update(
              "UPDATE record SET status = 'complete', updated_at = now() WHERE id = ? AND status = 'pdf_done'",
              recordId);
          // Check if there were any translation jobs at all
          Long totalTranslation =
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM job WHERE record_id = ? AND kind IN ('translate_page', 'translate_record')",
                  Long.class,
                  recordId);
          if (totalTranslation != null && totalTranslation > 0) {
            log.info("Record {} → complete (translation finished before pdf)", recordId);
            logPipelineEvent(recordId, "translation", "completed", "finished before pdf");
          } else {
            log.info("Record {} → complete (no translation needed)", recordId);
          }
        }
      }
      recordEventService.recordChanged(recordId, "status");
    }
  }

  private void logPipelineEvent(Long recordId, String stage, String event, String detail) {
    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, ?, ?, ?, now())",
        recordId,
        stage,
        event,
        detail);
  }

  private void checkRecordTranslationComplete(Long recordId) {
    Long pending =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE record_id = ? AND kind IN ('translate_page', 'translate_record') AND status != 'completed'",
            Long.class,
            recordId);
    if (pending != null && pending == 0) {
      logPipelineEvent(recordId, "translation", "completed", null);
      // If record is in 'translating', transition to 'complete'
      int updated =
          jdbcTemplate.update(
              "UPDATE record SET status = 'complete', updated_at = now() WHERE id = ? AND status = 'translating'",
              recordId);
      if (updated > 0) {
        log.info("Record {} → complete (all translation done)", recordId);
        recordEventService.recordChanged(recordId, "status");
      }
    }
  }

  /** Returns the Postgres NOTIFY channel name for a given job kind. */
  private static String channelForKind(String kind) {
    return switch (kind) {
      case "ocr_page_paddle", "ocr_page_abbyy" -> "ocr_jobs";
      case "build_searchable_pdf" -> "pdf_jobs";
      case "extract_entities" -> "entity_jobs";
      case "generate_thumbs" -> "ocr_jobs";
      case "translate_page", "translate_record" -> "translate_jobs";
      default -> "ocr_jobs";
    };
  }
}
