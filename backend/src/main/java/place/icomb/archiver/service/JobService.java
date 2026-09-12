package place.icomb.archiver.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
  private final PipelineGateService gateService;
  private final String defaultOcrEngine;
  private PipelineStateMachine stateMachine;

  public JobService(
      JobRepository jobRepository,
      JdbcTemplate jdbcTemplate,
      JobEventService jobEventService,
      RecordEventService recordEventService,
      PipelineGateService gateService,
      @Value("${archiver.ocr.default-engine:ocr_page_qwen3vl}") String defaultOcrEngine) {
    this.jobRepository = jobRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.jobEventService = jobEventService;
    this.recordEventService = recordEventService;
    this.gateService = gateService;
    this.defaultOcrEngine = defaultOcrEngine;
  }

  /** Injected after construction to break circular dependency (StateMachine → JobService). */
  void setStateMachine(PipelineStateMachine stateMachine) {
    this.stateMachine = stateMachine;
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
    // A paused kind is not claimed at all, so its jobs queue up rather than being cancelled.
    // This is how a stage is stopped without losing work — see PipelineGateService.
    if (gateService.isPaused(kind)) {
      return Optional.empty();
    }
    return jobRepository.findAndClaimNextJob(kind);
  }

  /**
   * Claims a batch of pending jobs, sized to a byte budget.
   *
   * <p>Deliberately alongside {@link #claimJob}: both routes into work must pass the same gate.
   * When the batch worker held its own claim SQL it bypassed this check entirely, so pausing a kind
   * stopped every worker except the one doing the most expensive work.
   *
   * @return the claimed jobs, empty when the kind is paused or nothing is pending
   */
  @Transactional
  public List<Job> claimBatch(String kind, int maxRows, long maxBytes, Long batchId) {
    if (gateService.isPaused(kind)) {
      return List.of();
    }
    return jobRepository.claimBatch(kind, maxRows, maxBytes, batchId);
  }

  /** As {@link #claimBatch}, for stages sized by count rather than by payload bytes. */
  @Transactional
  public List<Job> claimBatchByCount(String kind, int maxRows, Long batchId) {
    if (gateService.isPaused(kind)) {
      return List.of();
    }
    return jobRepository.claimBatchByCount(kind, maxRows, batchId);
  }

  /** Returns a batch's still-claimed jobs to the queue. */
  @Transactional
  public int releaseBatch(Long batchId, boolean restoreAttempt) {
    return jobRepository.releaseBatch(batchId, restoreAttempt);
  }

  public List<Job> findClaimedInBatch(Long batchId) {
    return jobRepository.findClaimedInBatch(batchId);
  }

  public int countSettledInBatch(Long batchId) {
    return jobRepository.countSettledInBatch(batchId);
  }

  /** Returns one job to the queue, optionally undoing its claim's attempt increment. */
  @Transactional
  public void releaseJob(Long jobId, boolean restoreAttempt) {
    jdbcTemplate.update(
        """
        UPDATE job SET status = 'pending', batch_id = NULL, started_at = NULL,
                       attempts = CASE WHEN ? THEN GREATEST(attempts - 1, 0) ELSE attempts END
        WHERE id = ? AND status = 'claimed'
        """,
        restoreAttempt,
        jobId);
  }

  public Optional<Job> findById(Long jobId) {
    return jobRepository.findById(jobId);
  }

  /** The page a job is for, or null if it has none. */
  public Long pageIdOf(Long jobId) {
    return jobRepository.findById(jobId).map(Job::getPageId).orElse(null);
  }

  /** Guards a result write against a re-read of the same provider output. */
  public boolean isStillClaimedBy(Long jobId, Long batchId) {
    return jobRepository.isStillClaimedBy(jobId, batchId) > 0;
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

    // Let the state machine evaluate guards and chain through any applicable transitions
    if (job.getRecordId() != null) {
      stateMachine.autoAdvance(job.getRecordId());
    }

    return job;
  }

  /**
   * Cleans up downstream pipeline data and resets a record to ocr_pending. Deletes old page_text,
   * text_chunks, searchable PDF, translations, and cancels pending downstream jobs.
   */
  @Transactional
  public void resetForOcr(Long recordId) {
    // Cancel pending/claimed downstream jobs
    jdbcTemplate.update(
        """
        UPDATE job SET status = 'completed', error = 'cancelled for ocr reset',
          finished_at = now()
        WHERE record_id = ?
          AND kind IN ('build_searchable_pdf', 'translate_page', 'translate_page_upgrade',
                       'translate_record', 'embed_record', 'match_persons')
          AND status IN ('pending', 'claimed')
        """,
        recordId);

    // Delete text chunks
    jdbcTemplate.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);

    // Delete page_text for all pages
    jdbcTemplate.update(
        "DELETE FROM page_text WHERE page_id IN (SELECT id FROM page WHERE record_id = ?)",
        recordId);

    // Delete searchable PDF attachment
    jdbcTemplate.update("UPDATE record SET pdf_attachment_id = NULL WHERE id = ?", recordId);
    jdbcTemplate.update(
        "DELETE FROM attachment WHERE record_id = ? AND role = 'searchable_pdf'", recordId);

    // Clear translated fields
    jdbcTemplate.update(
        "UPDATE record SET title_en = NULL, description_en = NULL, status = 'ocr_pending', updated_at = now() WHERE id = ?",
        recordId);

    logPipelineEvent(recordId, "ocr", "started", "reset for ocr");
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
  /**
   * How long a claimed job of each kind may run before it is presumed abandoned.
   *
   * <p>A single global threshold cannot distinguish a dead worker from a slow job. The previous
   * fixed 10 minutes was shorter than the observed maximum for three kinds — build_searchable_pdf
   * has taken 863s, translate_page 657s, ocr_page_qwen3vl 622s — so seven jobs were reset while
   * still running and a second worker re-executed them. That is duplicated work, duplicated API
   * spend, and before the UNIQUE constraint in V26 it also produced duplicate page_text rows.
   *
   * <p>Values are the measured maximum for the kind with generous headroom, not guesses. A kind
   * absent here uses {@link #DEFAULT_LEASE_SECONDS}; keeping that short means genuinely dead jobs
   * of ordinary kinds are recovered quickly.
   *
   * <p>Batched OCR is not here and must not be: a job handed to a provider's batch queue is
   * legitimately claimed for as long as that queue takes, so it is excluded by batch_id below
   * rather than given an ever-longer lease.
   */
  private static final Map<String, Integer> LEASE_SECONDS_BY_KIND =
      Map.of(
          "build_searchable_pdf", 3600,
          "translate_page", 1800,
          "translate_record", 1800,
          "ocr_page_qwen3vl", 1800,
          "ocr_page_claude", 1800);

  /** Lease for any kind not named above. */
  private static final int DEFAULT_LEASE_SECONDS = 600;

  /**
   * Returns jobs abandoned in {@code claimed} to the queue.
   *
   * <p>A worker that dies mid-job — or a backend restarted during a deploy — leaves its claim
   * behind forever: {@code claim} only ever selects {@code pending} rows, so nothing reclaims it
   * and the job's record stalls permanently. This is the single most important recovery step, and
   * the one most likely to be needed after a crash.
   *
   * <p>Jobs with a batch_id are excluded entirely. They are claimed by an OCR batch sitting at the
   * provider, and releasing one would resubmit pages that are already being billed — the single
   * largest double-billing hazard in the batch design. Those are owned by the batch worker's own
   * recovery, which reconciles against the provider rather than against a clock.
   *
   * <p>Deliberately its own transaction rather than a pass inside {@link #auditPipeline()}: it used
   * to share that method's transaction, so a failure in any later pass rolled this recovery back
   * along with it, leaving the jobs stuck exactly when something had already gone wrong.
   *
   * @return number of jobs returned to the queue
   */
  @Transactional
  public int recoverStaleClaims() {
    int reset = 0;
    for (var entry : LEASE_SECONDS_BY_KIND.entrySet()) {
      reset +=
          jdbcTemplate.update(
              """
              UPDATE job SET status = 'pending', started_at = NULL
              WHERE status = 'claimed'
                AND batch_id IS NULL
                AND kind = ?
                AND started_at < now() - make_interval(secs => ?)
              """,
              entry.getKey(),
              entry.getValue());
    }
    // Everything else falls back to the short default lease.
    List<Object> params = new ArrayList<>(LEASE_SECONDS_BY_KIND.keySet());
    String placeholders = "?,".repeat(params.size());
    params.add(DEFAULT_LEASE_SECONDS);
    reset +=
        jdbcTemplate.update(
            """
            UPDATE job SET status = 'pending', started_at = NULL
            WHERE status = 'claimed'
              AND batch_id IS NULL
              AND kind NOT IN (%s)
              AND started_at < now() - make_interval(secs => ?)
            """
                .formatted(placeholders.substring(0, placeholders.length() - 1)),
            params.toArray());

    if (reset > 0) {
      log.info("Audit: reset {} stale claimed jobs to pending", reset);
    }
    return reset;
  }

  /**
   * Resets a record to a specific pipeline stage, cleaning up downstream data and enqueuing new
   * jobs.
   *
   * @return summary map with recordId, targetStage, jobsEnqueued, jobsCancelled
   */
  @Transactional
  public Map<String, Object> resetRecordToStage(Long recordId, String targetStage) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String adminEmail = auth != null ? auth.getName() : "unknown";
    // Verify record exists
    Long exists =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM record WHERE id = ?", Long.class, recordId);
    if (exists == null || exists == 0) {
      throw new IllegalArgumentException("Record not found: " + recordId);
    }

    int jobsCancelled = 0;
    int jobsEnqueued = 0;

    switch (targetStage) {
      case "ocr_pending" -> {
        // Cancel all pending/claimed jobs for this record
        jobsCancelled =
            jdbcTemplate.update(
                """
                UPDATE job SET status = 'completed', error = 'cancelled by admin reset',
                  finished_at = now()
                WHERE record_id = ? AND status IN ('pending', 'claimed')
                """,
                recordId);

        // Delete text chunks
        jdbcTemplate.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);

        // Delete page_text for all pages
        jdbcTemplate.update(
            "DELETE FROM page_text WHERE page_id IN (SELECT id FROM page WHERE record_id = ?)",
            recordId);

        // Delete searchable PDF attachment
        jdbcTemplate.update(
            "DELETE FROM attachment WHERE record_id = ? AND role = 'searchable_pdf'", recordId);

        // Clear translated fields and pdf_attachment_id
        jdbcTemplate.update(
            "UPDATE record SET title_en = NULL, description_en = NULL, pdf_attachment_id = NULL, status = 'ocr_pending', updated_at = now() WHERE id = ?",
            recordId);

        // Enqueue OCR jobs for each page
        var langRow = jdbcTemplate.queryForMap("SELECT lang FROM record WHERE id = ?", recordId);
        String lang = (String) langRow.get("lang");
        String ocrPayload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
        List<Long> pageIds =
            jdbcTemplate.queryForList(
                "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recordId);
        for (Long pageId : pageIds) {
          enqueueJob(defaultOcrEngine, recordId, pageId, ocrPayload);
          jobsEnqueued++;
        }
      }

      case "translating" -> {
        // Cancel pending/claimed translate and embed jobs
        jobsCancelled =
            jdbcTemplate.update(
                """
                UPDATE job SET status = 'completed', error = 'cancelled by admin reset',
                  finished_at = now()
                WHERE record_id = ?
                  AND kind IN ('translate_page', 'translate_page_upgrade', 'translate_record',
                               'embed_record', 'match_persons')
                  AND status IN ('pending', 'claimed')
                """,
                recordId);

        // Delete text chunks
        jdbcTemplate.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);

        // Clear page translations
        jdbcTemplate.update(
            """
            UPDATE page_text SET text_en = NULL
            WHERE page_id IN (SELECT id FROM page WHERE record_id = ?)
            """,
            recordId);

        // Clear record translations
        jdbcTemplate.update(
            "UPDATE record SET title_en = NULL, description_en = NULL, status = 'translating', updated_at = now() WHERE id = ?",
            recordId);

        // Enqueue translate jobs
        var langRow =
            jdbcTemplate.queryForMap(
                "SELECT lang, metadata_lang, translation_quality FROM record WHERE id = ?",
                recordId);
        String contentLang = (String) langRow.get("lang");
        String metadataLang = (String) langRow.get("metadata_lang");
        // A record marked for the better translation must be re-queued at that quality. Enqueuing
        // plain translate_page here silently downgraded every upgraded record an admin reset.
        String translateKind =
            TranslationModels.jobKindFor((String) langRow.get("translation_quality"));

        // Metadata translation
        if (metadataLang == null || !"en".equals(metadataLang)) {
          String metaPayload = metadataLang != null ? "{\"lang\":\"" + metadataLang + "\"}" : null;
          enqueueJob("translate_record", recordId, null, metaPayload);
          jobsEnqueued++;
        }

        // Page translations
        if (contentLang == null || !"en".equals(contentLang)) {
          List<Long> pageIds =
              jdbcTemplate.queryForList(
                  "SELECT p.id FROM page p WHERE p.record_id = ? ORDER BY p.seq",
                  Long.class,
                  recordId);
          for (Long pageId : pageIds) {
            enqueueJob(translateKind, recordId, pageId, null);
            jobsEnqueued++;
          }
        }
      }

      case "embedding" -> {
        // Cancel pending/claimed embed jobs
        jobsCancelled =
            jdbcTemplate.update(
                """
                UPDATE job SET status = 'completed', error = 'cancelled by admin reset',
                  finished_at = now()
                WHERE record_id = ? AND kind IN ('embed_record', 'match_persons')
                  AND status IN ('pending', 'claimed')
                """,
                recordId);

        // Delete text chunks
        jdbcTemplate.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);

        // Set status
        jdbcTemplate.update(
            "UPDATE record SET status = 'embedding', updated_at = now() WHERE id = ?", recordId);

        // Enqueue embed job
        enqueueJob("embed_record", recordId, null, null);
        jobsEnqueued++;
      }

      default -> throw new IllegalArgumentException("Invalid target stage: " + targetStage);
    }

    // Log pipeline event
    logPipelineEvent(
        recordId, "admin", "admin_reset", "Reset to " + targetStage + " by " + adminEmail);

    // Notify UI
    recordEventService.recordChanged(recordId, "status");

    log.info(
        "Admin reset: record {} → {} by {} ({} jobs cancelled, {} jobs enqueued)",
        recordId,
        targetStage,
        adminEmail,
        jobsCancelled,
        jobsEnqueued);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("recordId", recordId);
    result.put("targetStage", targetStage);
    result.put("jobsEnqueued", jobsEnqueued);
    result.put("jobsCancelled", jobsCancelled);
    return result;
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

  private void logPipelineEvent(Long recordId, String stage, String event, String detail) {
    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, ?, ?, ?, now())",
        recordId,
        stage,
        event,
        detail);
  }

  /** Returns the Postgres NOTIFY channel name for a given job kind. */
  private static String channelForKind(String kind) {
    return switch (kind) {
      case "build_searchable_pdf" -> "pdf_jobs";
      case "generate_thumbs" -> "ocr_jobs";
      case "translate_page", "translate_record" -> "translate_jobs";
      case "embed_record" -> "embed_jobs";
      case "match_persons" -> "match_jobs";
      default -> "ocr_jobs";
    };
  }
}
