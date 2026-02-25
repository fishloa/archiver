package place.icomb.archiver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically audits the pipeline for records that got stuck between status transitions due to
 * deployments or bugs that pre-date certain pipeline stages.
 */
@Service
public class PipelineAuditScheduler {

  private static final Logger log = LoggerFactory.getLogger(PipelineAuditScheduler.class);

  private final JobService jobService;

  public PipelineAuditScheduler(JobService jobService) {
    this.jobService = jobService;
  }

  /** Run audit immediately on application startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    log.info("Running pipeline audit on startup...");
    runAudit();
  }

  /** Runs every 30 minutes after the previous run completes. */
  @Scheduled(fixedDelay = 1_800_000)
  public void runAudit() {
    try {
      int count = jobService.auditPipeline();
      if (count > 0) {
        log.info("Pipeline audit fixed {} record(s)/job(s)", count);
      }
    } catch (Exception e) {
      log.error("Pipeline audit failed", e);
    }
  }
}
