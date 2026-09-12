package place.icomb.archiver.service;

import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;

/**
 * Periodically audits the pipeline for records and jobs that got stuck — most importantly jobs left
 * in {@code claimed} by a worker that died or a backend that restarted mid-flight, which no other
 * mechanism ever returns to the queue.
 *
 * <p>Runs on its own dedicated single-thread scheduler rather than the shared one configured by
 * {@link place.icomb.archiver.config.WorkerSchedulingConfig}. That pool is sized around the
 * internal OCR workers, and when they were all busy this task simply never ran: recovery starved
 * exactly when the pipeline was under the load that causes stuck jobs in the first place. An
 * isolated thread means a saturated worker pool can no longer suppress recovery.
 */
@Service
public class PipelineAuditScheduler {

  private static final Logger log = LoggerFactory.getLogger(PipelineAuditScheduler.class);

  /**
   * Dedicated scheduler so worker saturation can never delay recovery.
   *
   * <p>Named distinctly from this class: a @Bean method named after its enclosing @Service collides
   * with the component-scanned bean of the same (camel-cased) name and the context fails to start.
   */
  @Bean(name = "auditTaskScheduler", destroyMethod = "")
  public static TaskScheduler auditTaskScheduler() {
    return new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor());
  }

  private final JobService jobService;
  private final PipelineAuditService auditService;

  public PipelineAuditScheduler(JobService jobService, PipelineAuditService auditService) {
    this.jobService = jobService;
    this.auditService = auditService;
  }

  /** Run audit immediately on application startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    log.info("Running pipeline audit on startup...");
    runAudit();
  }

  /**
   * Runs every minute after the previous run completes. A stuck job blocks a record's whole
   * downstream pipeline, so the cost of checking often is far below the cost of a record sitting
   * dead until someone notices.
   */
  @Scheduled(fixedDelay = 60_000, scheduler = "auditTaskScheduler")
  public void runAudit() {
    try {
      // Recovery first, and in its own transaction: if auditPipeline throws, jobs abandoned
      // by a crashed worker have still been returned to the queue.
      int count = jobService.recoverStaleClaims();
      count += auditService.auditPipeline();
      if (count > 0) {
        log.info("Pipeline audit fixed {} record(s)/job(s)", count);
      }
    } catch (Exception e) {
      log.error("Pipeline audit failed", e);
    }
  }
}
