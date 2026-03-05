package place.icomb.archiver.service;

import java.util.Optional;
import org.slf4j.Logger;
import place.icomb.archiver.model.Job;

/**
 * Base class for internal workers that poll for jobs and process them single-threaded. Scale by
 * creating N instances via {@link place.icomb.archiver.config.WorkerSchedulingConfig}.
 */
public abstract class GenericWorker {

  private final JobService jobService;
  private final JobEventService jobEventService;
  private final String jobKind;
  private final String workerId;

  protected GenericWorker(
      JobService jobService, JobEventService jobEventService, String jobKind, String workerId) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.jobKind = jobKind;
    this.workerId = workerId;
  }

  protected abstract void processJob(Job job) throws Exception;

  protected abstract Logger log();

  /** Polls for jobs and processes them one at a time until the queue is empty. */
  public void pollAndProcess() {
    jobEventService.touchWorker(workerId, jobKind);

    while (true) {
      Optional<Job> claimed = jobService.claimJob(jobKind);
      if (claimed.isEmpty()) break;

      Job job = claimed.get();
      long start = System.currentTimeMillis();
      try {
        processJob(job);
        jobService.completeJob(job.getId(), null);
        long elapsed = System.currentTimeMillis() - start;
        log()
            .info(
                "{} completed: job={} record={} ({}ms)",
                workerId,
                job.getId(),
                job.getRecordId(),
                elapsed);
      } catch (Exception e) {
        log()
            .error(
                "{} failed: job={} record={}: {}",
                workerId,
                job.getId(),
                job.getRecordId(),
                e.getMessage(),
                e);
        jobService.failJob(job.getId(), e.getMessage());
      }
    }
  }

  public String getWorkerId() {
    return workerId;
  }

  public String getJobKind() {
    return jobKind;
  }
}
