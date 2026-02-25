package place.icomb.archiver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically audits the pipeline for records that got stuck between status transitions
 * due to deployments or bugs that pre-date certain pipeline stages.
 */
@Service
public class PipelineAuditScheduler {

  private static final Logger log = LoggerFactory.getLogger(PipelineAuditScheduler.class);

  private final JobService jobService;

  public PipelineAuditScheduler(JobService jobService) {
    this.jobService = jobService;
  }

  /**
   * Runs every 30 minutes (fixed delay between end of last run and start of next).
   * Finds and re-queues records stuck in ocr_done or pdf_pending.
   */
  @Scheduled(fixedDelay = 1_800_000)
  public void runAudit() {
    log.info("Running scheduled pipeline audit...");
    try {
      int count = jobService.auditPipeline();
      if (count > 0) {
        log.info("Scheduled pipeline audit re-queued/nudged {} record(s)", count);
      }
    } catch (Exception e) {
      log.error("Scheduled pipeline audit failed", e);
    }
  }
}
