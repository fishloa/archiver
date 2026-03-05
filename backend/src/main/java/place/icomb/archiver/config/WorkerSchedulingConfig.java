package place.icomb.archiver.config;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.service.JobEventService;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchWorker;
import place.icomb.archiver.service.QwenOcrWorker;
import place.icomb.archiver.service.StorageService;

/**
 * Dynamically registers N scheduled tasks per internal worker type based on configuration. Each
 * instance is single-threaded — concurrency is achieved by running multiple instances.
 */
@Configuration
public class WorkerSchedulingConfig implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WorkerSchedulingConfig.class);

  private final JobService jobService;
  private final JobEventService jobEventService;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final PersonMatchService personMatchService;

  private final boolean qwenEnabled;
  private final String qwenBaseUrl;
  private final String qwenApiKey;
  private final String qwenModel;
  private final int qwenConcurrency;
  private final long qwenPollInterval;

  private final boolean personMatchEnabled;
  private final long personMatchPollInterval;

  public WorkerSchedulingConfig(
      JobService jobService,
      JobEventService jobEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      PersonMatchService personMatchService,
      @Value("${archiver.ocr.qwen.enabled:false}") boolean qwenEnabled,
      @Value("${archiver.ocr.qwen.base-url:}") String qwenBaseUrl,
      @Value("${archiver.ocr.qwen.api-key:}") String qwenApiKey,
      @Value("${archiver.ocr.qwen.model:}") String qwenModel,
      @Value("${archiver.ocr.qwen.concurrency:1}") int qwenConcurrency,
      @Value("${archiver.ocr.qwen.poll-interval:5000}") long qwenPollInterval,
      @Value("${archiver.person-match.enabled:true}") boolean personMatchEnabled,
      @Value("${archiver.person-match.poll-interval:5000}") long personMatchPollInterval) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.personMatchService = personMatchService;
    this.qwenEnabled = qwenEnabled;
    this.qwenBaseUrl = qwenBaseUrl;
    this.qwenApiKey = qwenApiKey;
    this.qwenModel = qwenModel;
    this.qwenConcurrency = qwenConcurrency;
    this.qwenPollInterval = qwenPollInterval;
    this.personMatchEnabled = personMatchEnabled;
    this.personMatchPollInterval = personMatchPollInterval;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    int totalWorkers = (qwenEnabled ? qwenConcurrency : 0) + (personMatchEnabled ? 1 : 0);
    if (totalWorkers == 0) return;

    registrar.setScheduler(Executors.newScheduledThreadPool(totalWorkers));

    if (qwenEnabled) {
      for (int i = 0; i < qwenConcurrency; i++) {
        var worker =
            new QwenOcrWorker(
                "qwen-ocr-" + i,
                jobService,
                jobEventService,
                pageRepository,
                attachmentRepository,
                storageService,
                pageTextRepository,
                qwenBaseUrl,
                qwenApiKey,
                qwenModel);
        registrar.addFixedDelayTask(worker::pollAndProcess, Duration.ofMillis(qwenPollInterval));
      }
      log.info(
          "Registered {} Qwen OCR worker(s) (base-url={}, model={}, poll={}ms)",
          qwenConcurrency,
          qwenBaseUrl,
          qwenModel,
          qwenPollInterval);
    }

    if (personMatchEnabled) {
      var worker =
          new PersonMatchWorker("person-match-0", jobService, jobEventService, personMatchService);
      registrar.addFixedDelayTask(
          worker::pollAndProcess, Duration.ofMillis(personMatchPollInterval));
      log.info("Registered person match worker (poll={}ms)", personMatchPollInterval);
    }
  }
}
