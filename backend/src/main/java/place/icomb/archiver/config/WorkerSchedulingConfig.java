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
import place.icomb.archiver.repository.PageTranslationRepository;
import place.icomb.archiver.repository.ProviderBatchRepository;
import place.icomb.archiver.service.BatchOrchestrator;
import place.icomb.archiver.service.ClaudeOcrWorker;
import place.icomb.archiver.service.JobEventService;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.MistralBatchClient;
import place.icomb.archiver.service.OcrBatchStage;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchWorker;
import place.icomb.archiver.service.QwenOcrWorker;
import place.icomb.archiver.service.RecordEventService;
import place.icomb.archiver.service.RecordTranslateBatchStage;
import place.icomb.archiver.service.StorageService;
import place.icomb.archiver.service.TranslateBatchStage;
import place.icomb.archiver.service.TranslationModels;

/**
 * Dynamically registers N scheduled tasks per internal worker type based on configuration. Each
 * instance is single-threaded — concurrency is achieved by running multiple instances.
 */
@Configuration
public class WorkerSchedulingConfig implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WorkerSchedulingConfig.class);

  private final JobService jobService;
  private final JobEventService jobEventService;
  private final RecordEventService recordEventService;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final ProviderBatchRepository providerBatchRepository;
  private final PageTranslationRepository pageTranslationRepository;
  private final PersonMatchService personMatchService;
  private final place.icomb.archiver.service.TranslationService translationService;
  private final place.icomb.archiver.service.PdfExportService pdfExportService;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  private final boolean qwenEnabled;
  private final String qwenBaseUrl;
  private final String qwenApiKey;
  private final String qwenModel;
  private final int qwenConcurrency;
  private final long qwenPollInterval;

  private final boolean claudeOcrEnabled;
  private final String claudeApiKey;
  private final String claudeModel;
  private final int claudeConcurrency;
  private final long claudePollInterval;

  private final boolean mistralOcrEnabled;
  private final String mistralApiKey;
  private final String mistralModel;
  private final String mistralBaseUrl;
  private final long mistralTickInterval;
  private final int mistralMaxBatchPages;
  private final long mistralMaxBatchBytes;
  private final int mistralPagesPerMinute;
  private final boolean translateBatchEnabled;
  private final boolean translateRecordEnabled;
  private final String translateBatchModel;
  private final int translateBatchSize;
  private final int translatePerMinute;

  private final boolean personMatchEnabled;
  private final long personMatchPollInterval;

  private final int pdfConcurrency;
  private final long pdfPollInterval;

  public WorkerSchedulingConfig(
      JobService jobService,
      JobEventService jobEventService,
      RecordEventService recordEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      ProviderBatchRepository providerBatchRepository,
      PageTranslationRepository pageTranslationRepository,
      PersonMatchService personMatchService,
      place.icomb.archiver.service.TranslationService translationService,
      place.icomb.archiver.service.PdfExportService pdfExportService,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
      @Value("${archiver.ocr.qwen.enabled:false}") boolean qwenEnabled,
      @Value("${archiver.ocr.qwen.base-url:}") String qwenBaseUrl,
      @Value("${archiver.ocr.qwen.api-key:}") String qwenApiKey,
      @Value("${archiver.ocr.qwen.model:}") String qwenModel,
      @Value("${archiver.ocr.qwen.concurrency:1}") int qwenConcurrency,
      @Value("${archiver.ocr.qwen.poll-interval:5000}") long qwenPollInterval,
      @Value("${archiver.ocr.claude.enabled:false}") boolean claudeOcrEnabled,
      @Value("${archiver.ocr.claude.api-key:}") String claudeApiKey,
      @Value("${archiver.ocr.claude.model:claude-haiku-4-5-20251001}") String claudeModel,
      @Value("${archiver.ocr.claude.concurrency:1}") int claudeConcurrency,
      @Value("${archiver.ocr.claude.poll-interval:5000}") long claudePollInterval,
      @Value("${archiver.ocr.mistral.enabled:false}") boolean mistralOcrEnabled,
      @Value("${archiver.ocr.mistral.api-key:}") String mistralApiKey,
      @Value("${archiver.ocr.mistral.model:mistral-ocr-latest}") String mistralModel,
      @Value("${archiver.ocr.mistral.base-url:https://api.mistral.ai}") String mistralBaseUrl,
      @Value("${archiver.ocr.mistral.tick-interval:15000}") long mistralTickInterval,
      @Value("${archiver.ocr.mistral.max-batch-pages:1000}") int mistralMaxBatchPages,
      @Value("${archiver.ocr.mistral.max-batch-bytes:209715200}") long mistralMaxBatchBytes,
      @Value("${archiver.ocr.mistral.pages-per-minute:1250}") int mistralPagesPerMinute,
      @Value("${archiver.translate.batch.enabled:false}") boolean translateBatchEnabled,
      @Value("${archiver.translate.record.enabled:false}") boolean translateRecordEnabled,
      @Value("${archiver.translate.batch.model:mistral-small-latest}") String translateBatchModel,
      @Value("${archiver.translate.batch.size:2000}") int translateBatchSize,
      @Value("${archiver.translate.batch.pages-per-minute:5000}") int translatePerMinute,
      @Value("${archiver.person-match.enabled:true}") boolean personMatchEnabled,
      @Value("${archiver.person-match.poll-interval:5000}") long personMatchPollInterval,
      @Value("${archiver.pdf.concurrency:3}") int pdfConcurrency,
      @Value("${archiver.pdf.poll-interval:5000}") long pdfPollInterval) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.recordEventService = recordEventService;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.providerBatchRepository = providerBatchRepository;
    this.pageTranslationRepository = pageTranslationRepository;
    this.personMatchService = personMatchService;
    this.jdbcTemplate = jdbcTemplate;
    this.qwenEnabled = qwenEnabled;
    this.qwenBaseUrl = qwenBaseUrl;
    this.qwenApiKey = qwenApiKey;
    this.qwenModel = qwenModel;
    this.qwenConcurrency = qwenConcurrency;
    this.qwenPollInterval = qwenPollInterval;
    this.claudeOcrEnabled = claudeOcrEnabled;
    this.claudeApiKey = claudeApiKey;
    this.claudeModel = claudeModel;
    this.claudeConcurrency = claudeConcurrency;
    this.claudePollInterval = claudePollInterval;
    this.mistralOcrEnabled = mistralOcrEnabled;
    this.mistralApiKey = mistralApiKey;
    this.mistralModel = mistralModel;
    this.mistralBaseUrl = mistralBaseUrl;
    this.mistralTickInterval = mistralTickInterval;
    this.mistralMaxBatchPages = mistralMaxBatchPages;
    this.mistralMaxBatchBytes = mistralMaxBatchBytes;
    this.mistralPagesPerMinute = mistralPagesPerMinute;
    this.translateBatchEnabled = translateBatchEnabled;
    this.translateRecordEnabled = translateRecordEnabled;
    this.translateBatchModel = translateBatchModel;
    this.translateBatchSize = translateBatchSize;
    this.translatePerMinute = translatePerMinute;
    this.personMatchEnabled = personMatchEnabled;
    this.personMatchPollInterval = personMatchPollInterval;
    this.translationService = translationService;
    this.pdfExportService = pdfExportService;
    this.pdfConcurrency = pdfConcurrency;
    this.pdfPollInterval = pdfPollInterval;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    int totalWorkers =
        (qwenEnabled ? qwenConcurrency : 0)
            + (claudeOcrEnabled ? claudeConcurrency : 0)
            + (mistralOcrEnabled ? 1 : 0)
            + (translateBatchEnabled ? 2 : 0)
            + (translateRecordEnabled ? 1 : 0)
            + (personMatchEnabled ? 1 : 0)
            + pdfConcurrency;
    if (totalWorkers == 0) return;

    // Headroom above the worker count. This pool also serves every other @Scheduled bean in
    // the application, so sizing it to exactly totalWorkers left no thread for them: whenever
    // all workers were busy, PipelineAuditScheduler could not run — meaning the mechanism that
    // recovers stuck jobs starved precisely when jobs were most likely to get stuck. Observed
    // in production as the audit running every 5 minutes while idle, then going silent for an
    // hour under load while claimed jobs sat orphaned. The audit additionally runs on its own
    // scheduler (see PipelineAuditScheduler) so it cannot be starved even if this fills up.
    registrar.setScheduler(Executors.newScheduledThreadPool(totalWorkers + 2));

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

    if (claudeOcrEnabled) {
      for (int i = 0; i < claudeConcurrency; i++) {
        var worker =
            new ClaudeOcrWorker(
                "claude-ocr-" + i,
                jobService,
                jobEventService,
                pageRepository,
                attachmentRepository,
                storageService,
                pageTextRepository,
                claudeApiKey,
                claudeModel);
        registrar.addFixedDelayTask(worker::pollAndProcess, Duration.ofMillis(claudePollInterval));
      }
      log.info(
          "Registered {} Claude OCR worker(s) (model={}, poll={}ms)",
          claudeConcurrency,
          claudeModel,
          claudePollInterval);
    }

    if (mistralOcrEnabled) {
      var client = new MistralBatchClient(mistralApiKey, mistralBaseUrl);

      // OCR. One orchestrator instance per stage: the phases run in sequence, so nothing needs
      // locking, and throughput comes from batch size rather than thread count.
      var ocrStage =
          new OcrBatchStage(
              mistralModel,
              pageId -> {
                var page =
                    pageRepository
                        .findById(pageId)
                        .orElseThrow(() -> new IllegalStateException("Page not found: " + pageId));
                var attachment =
                    attachmentRepository
                        .findById(page.getAttachmentId())
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Attachment not found: " + page.getAttachmentId()));
                return storageService.getPath(attachment);
              },
              pageTextRepository,
              jobService);
      var ocr =
          new BatchOrchestrator(
              "mistral-batch-ocr",
              ocrStage,
              client,
              jobService,
              jobEventService,
              recordEventService,
              providerBatchRepository,
              mistralMaxBatchPages,
              mistralMaxBatchBytes,
              mistralPagesPerMinute);
      registrar.addFixedDelayTask(ocr::tick, Duration.ofMillis(mistralTickInterval));

      log.info(
          "Registered Mistral batch OCR (model={}, tick={}ms, max-batch={} bytes, {} pages/min)",
          mistralModel,
          mistralTickInterval,
          mistralMaxBatchBytes,
          mistralPagesPerMinute);
    }

    if (translateBatchEnabled) {
      var client = new MistralBatchClient(mistralApiKey, mistralBaseUrl);

      // Bulk translation. Cheap model, whole archive.
      var bulk =
          new BatchOrchestrator(
              "mistral-batch-translate",
              new TranslateBatchStage(
                  TranslationModels.BULK_MODEL, "translate_page", jdbcTemplate, translationService),
              client,
              jobService,
              jobEventService,
              recordEventService,
              providerBatchRepository,
              translateBatchSize,
              mistralMaxBatchBytes,
              translatePerMinute);
      registrar.addFixedDelayTask(bulk::tick, Duration.ofMillis(mistralTickInterval));

      // On-demand upgrades. A separate kind because a batch carries one model, and separate
      // so an upgrade queue can be held or drained independently of the bulk run.
      var upgrade =
          new BatchOrchestrator(
              "mistral-batch-translate-upgrade",
              new TranslateBatchStage(
                  TranslationModels.UPGRADE_MODEL,
                  "translate_page_upgrade",
                  jdbcTemplate,
                  translationService),
              client,
              jobService,
              jobEventService,
              recordEventService,
              providerBatchRepository,
              translateBatchSize,
              mistralMaxBatchBytes,
              translatePerMinute);
      registrar.addFixedDelayTask(upgrade::tick, Duration.ofMillis(mistralTickInterval));

      log.info(
          "Registered Mistral batch translation (bulk={}, upgrade={}, metadata={}, batch={})",
          TranslationModels.BULK_MODEL,
          TranslationModels.UPGRADE_MODEL,
          TranslationModels.UPGRADE_MODEL,
          translateBatchSize);
    }

    // Searchable PDFs are built in-process rather than by a separate service: the invisible
    // text layer needs the OCR block coordinates and an embedded Unicode font, both of which
    // already exist here for the on-the-fly exports. A small pool because the work is IO-bound
    // on reading scans and each job holds a whole record's images in turn.
    for (int i = 0; i < pdfConcurrency; i++) {
      var worker =
          new place.icomb.archiver.service.SearchablePdfWorker(
              "searchable-pdf-" + i,
              jobService,
              jobEventService,
              pdfExportService,
              storageService,
              attachmentRepository);
      registrar.addFixedDelayTask(worker::pollAndProcess, Duration.ofMillis(pdfPollInterval));
    }
    if (pdfConcurrency > 0) {
      log.info(
          "Registered {} searchable PDF worker(s) (poll={}ms)", pdfConcurrency, pdfPollInterval);
    }

    // Record metadata translation, flagged separately from page translation.
    //
    // Sharing one flag would mean that turning metadata translation on also armed the bulk and
    // upgrade page orchestrators — 128,484 pages of already-translated work standing behind a
    // single environment variable. Titles are a few hundred characters; they should not be
    // gated on the same switch as the archive.
    if (translateRecordEnabled) {
      var recordClient = new MistralBatchClient(mistralApiKey, mistralBaseUrl);
      var metadata =
          new BatchOrchestrator(
              "mistral-batch-translate-record",
              new RecordTranslateBatchStage(
                  TranslationModels.UPGRADE_MODEL, jdbcTemplate, translationService),
              recordClient,
              jobService,
              jobEventService,
              recordEventService,
              providerBatchRepository,
              translateBatchSize,
              mistralMaxBatchBytes,
              translatePerMinute);
      registrar.addFixedDelayTask(metadata::tick, Duration.ofMillis(mistralTickInterval));
      log.info(
          "Registered Mistral batch record-metadata translation (model={}, batch={})",
          TranslationModels.UPGRADE_MODEL,
          translateBatchSize);
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
