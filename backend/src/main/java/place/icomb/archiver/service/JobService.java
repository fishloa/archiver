package place.icomb.archiver.service;

import java.time.Instant;
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

    return job;
  }

  /**
   * If every page in the record has OCR text, transition the record status from ocr_pending to
   * ocr_complete.
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
              "UPDATE record SET status = 'ocr_complete', updated_at = now() WHERE id = ? AND status = 'ocr_pending'",
              recordId);
      if (updated > 0) {
        log.info("Record {} transitioned to ocr_complete", recordId);
        recordEventService.recordChanged(recordId, "status");
      }
    }
  }

  private static boolean isOcrKind(String kind) {
    return kind != null && kind.startsWith("ocr_page_");
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

  /** Returns the Postgres NOTIFY channel name for a given job kind. */
  private static String channelForKind(String kind) {
    return switch (kind) {
      case "ocr_page_paddle", "ocr_page_abbyy" -> "ocr_jobs";
      case "build_searchable_pdf" -> "pdf_jobs";
      case "extract_entities" -> "entity_jobs";
      case "generate_thumbs" -> "ocr_jobs";
      default -> "ocr_jobs";
    };
  }
}
