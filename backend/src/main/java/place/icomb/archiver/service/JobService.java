package place.icomb.archiver.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.repository.JobRepository;

@Service
public class JobService {

  private final JobRepository jobRepository;
  private final JdbcTemplate jdbcTemplate;

  public JobService(JobRepository jobRepository, JdbcTemplate jdbcTemplate) {
    this.jobRepository = jobRepository;
    this.jdbcTemplate = jdbcTemplate;
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

    // Fire NOTIFY on the appropriate channel
    String channel = channelForKind(kind);
    jdbcTemplate.execute("NOTIFY " + channel);

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
    return jobRepository.save(job);
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
    return jobRepository.save(job);
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
