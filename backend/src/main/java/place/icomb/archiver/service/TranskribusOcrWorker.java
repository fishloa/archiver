package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.ai.TranskribusConfig;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * Transcribes handwritten pages with Transkribus, one page per claimed job.
 *
 * <p>Deliberately not part of the ordinary pipeline. Transkribus bills a credit per page against a
 * monthly allowance — 50 on the free plan — so pages arrive here only because somebody asked for
 * them, through {@code POST /api/admin/enqueue-transkribus}.
 *
 * <p>The worker refuses to start a page once the month's credits are spent, and checks before
 * claiming rather than after: a job that fails for want of credit is a job that has to be found and
 * re-queued, and on a metered service a loop that keeps trying is a bill.
 */
public class TranskribusOcrWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(TranskribusOcrWorker.class);

  /** Transkribus accepts up to ~21 MB per image; well above a 300 dpi A4 page, but not a TIFF. */
  private static final int MAX_IMAGE_DIMENSION = 4000;

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final JdbcTemplate jdbcTemplate;
  private final TranskribusConfig config;
  private final TranskribusClient client;
  private final PipelineStateMachine stateMachine;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TranskribusOcrWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      JdbcTemplate jdbcTemplate,
      TranskribusConfig config,
      TranskribusClient client,
      PipelineStateMachine stateMachine) {
    super(jobService, jobEventService, TranskribusConfig.JOB_KIND, workerId);
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.config = config;
    this.client = client;
    this.stateMachine = stateMachine;
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String model() {
    return config.model();
  }

  @Override
  protected String providerUrl() {
    return config.baseUrl();
  }

  /** Skips the whole pass when the month's credits are gone, so no job is claimed and failed. */
  @Override
  public void pollAndProcess() {
    int spent = creditsSpentThisMonth();
    if (spent >= config.monthlyCredits()) {
      log.debug(
          "Transkribus: {}/{} credits used this month, holding jobs until it resets",
          spent,
          config.monthlyCredits());
      return;
    }
    super.pollAndProcess();
  }

  /**
   * Pages transcribed by Transkribus since the start of this calendar month.
   *
   * <p>Counted from what was written rather than from a running total, so a restart, a redeploy or
   * a second worker instance cannot lose count and overspend.
   */
  int creditsSpentThisMonth() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM page_text
            WHERE engine LIKE 'transkribus%'
              AND created_at >= date_trunc('month', now())
            """,
            Integer.class);
    return count == null ? 0 : count;
  }

  @Override
  protected void processJob(Job job) throws Exception {
    int spent = creditsSpentThisMonth();
    if (spent >= config.monthlyCredits()) {
      throw new IllegalStateException(
          "Transkribus monthly credits exhausted (%d/%d); re-queue this page next month"
              .formatted(spent, config.monthlyCredits()));
    }

    Page page =
        pageRepository
            .findById(job.getPageId())
            .orElseThrow(() -> new IllegalStateException("Page not found: " + job.getPageId()));
    Attachment attachment =
        attachmentRepository
            .findById(page.getAttachmentId())
            .orElseThrow(
                () -> new IllegalStateException("Attachment not found: " + page.getAttachmentId()));

    JsonNode payload =
        job.getPayload() == null
            ? objectMapper.createObjectNode()
            : objectMapper.readTree(job.getPayload());
    String lang = payload.path("lang").asText(null);
    int htrId = chooseModel(payload, lang);

    Path imagePath = storageService.getPath(attachment);
    byte[] imageBytes = downsizeIfNeeded(imagePath);

    TranskribusClient.Result result = client.transcribe(imageBytes, htrId);

    if (result.text() == null || result.text().isBlank()) {
      // A blank result still cost a credit, so say so rather than storing an empty transcription
      // that would read as "this page is blank".
      throw new IllegalStateException(
          "Transkribus process %d returned no text for page %d (credit spent)"
              .formatted(result.processId(), page.getId()));
    }

    // Line and word geometry, which Mistral does not provide at all: it returns bounding boxes for
    // figures only. Kept so the searchable PDF's invisible text layer can be positioned over the
    // words it transcribes rather than laid over the page as a block.
    String pageXml = null;
    try {
      pageXml = client.pageXml(result.processId());
    } catch (Exception e) {
      log.warn(
          "Transkribus process {}: text saved but PAGE XML could not be fetched: {}",
          result.processId(),
          e.getMessage());
    }

    // Replace rather than append: page_text is UNIQUE on page_id, and the history trigger copies
    // the outgoing transcription to page_ocr_history, so the Mistral attempt survives as a record
    // even though its text does not.
    pageTextRepository.deleteByPageId(page.getId());

    PageText pt = new PageText();
    pt.setPageId(page.getId());
    // The exact model is part of the provenance: "transkribus" alone would not say whether a page
    // was read by a free super model or by Text Titan II.
    pt.setEngine("transkribus:" + htrId);
    pt.setContentType(OcrContentType.PLAIN);
    pt.setTextRaw(result.text());
    pt.setHocr(pageXml);
    pt.setCreatedAt(Instant.now());
    pageTextRepository.save(pt);

    log.info(
        "Transkribus OCR: page={} record={} htrId={} process={} chars={} credits={}/{}",
        page.getId(),
        job.getRecordId(),
        htrId,
        result.processId(),
        result.text().length(),
        spent + 1,
        config.monthlyCredits());

    // Carry this one page through the rest of the pipeline. Re-running the record would re-OCR
    // every other page on the default engine and re-translate the lot; the point of a per-page
    // re-transcription is that only the page that was wrong changes.
    if (!"none".equalsIgnoreCase(payload.path("andThen").asText("full"))) {
      stateMachine.advanceSinglePage(job.getRecordId(), page.getId());
    }
  }

  /**
   * Which model to apply.
   *
   * <p>An explicit {@code htrId} in the job payload wins, so an operator can try any model from
   * Transkribus's catalogue on one page without touching configuration. Failing that, a page the
   * caller marked as typescript goes to the print model and everything else to the handwriting
   * model for its language.
   */
  int chooseModel(JsonNode payload, String lang) {
    int explicit = payload.path("htrId").asInt(0);
    if (explicit > 0) {
      return explicit;
    }
    return "print".equalsIgnoreCase(payload.path("pageClass").asText(""))
        ? config.printHtrId(lang)
        : config.htrId(lang);
  }

  private byte[] downsizeIfNeeded(Path imagePath) throws Exception {
    BufferedImage img = ImageIO.read(imagePath.toFile());
    if (img == null) {
      throw new IllegalStateException("Could not read page image: " + imagePath);
    }
    int longest = Math.max(img.getWidth(), img.getHeight());
    if (longest <= MAX_IMAGE_DIMENSION) {
      return Files.readAllBytes(imagePath);
    }
    double scale = (double) MAX_IMAGE_DIMENSION / longest;
    int width = (int) (img.getWidth() * scale);
    int height = (int) (img.getHeight() * scale);
    BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = resized.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(img, 0, 0, width, height, null);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(resized, "jpg", out);
    log.debug(
        "Downscaled {}x{} to {}x{} for Transkribus",
        img.getWidth(),
        img.getHeight(),
        width,
        height);
    return out.toByteArray();
  }
}
