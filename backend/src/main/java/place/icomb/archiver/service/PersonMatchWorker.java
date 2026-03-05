package place.icomb.archiver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.model.Job;

/**
 * Internal worker that claims {@code match_persons} jobs and runs person matching on the record's
 * pages. Instances are created by {@link place.icomb.archiver.config.WorkerSchedulingConfig}.
 */
public class PersonMatchWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(PersonMatchWorker.class);
  private static final String JOB_KIND = "match_persons";

  private final PersonMatchService personMatchService;

  public PersonMatchWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      PersonMatchService personMatchService) {
    super(jobService, jobEventService, JOB_KIND, workerId);
    this.personMatchService = personMatchService;
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected void processJob(Job job) throws Exception {
    personMatchService.matchRecord(job.getRecordId());
  }
}
