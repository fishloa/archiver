package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * Internal OCR worker that claims {@code ocr_page_mistral} jobs and processes them via Mistral's
 * dedicated OCR endpoint.
 *
 * <p>This is deliberately not routed through {@link QwenOcrWorker}: Mistral's OCR model lives on
 * {@code POST /v1/ocr} rather than the OpenAI-compatible chat endpoint, takes no prompt, and
 * returns {@code pages[].markdown} instead of {@code choices[].message.content}. It is billed per
 * page, not per token.
 *
 * <p>Benchmarked against Claude Opus 5 over a 38-page German typescript record: 0.975 word-level
 * similarity and 2.4% word loss, against 0.965 for a Mistral Small 4 vision model on the same
 * pages. Purpose-trained document OCR transcribes the folio stamps and header furniture that vision
 * LLMs skip as unimportant.
 *
 * <p>Instances are created by {@link place.icomb.archiver.config.WorkerSchedulingConfig}.
 */
public class MistralOcrWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(MistralOcrWorker.class);
  private static final String JOB_KIND = "ocr_page_mistral";
  private static final int MAX_IMAGE_DIMENSION = 2048;
  private static final int MAX_ATTEMPTS = 4;
  private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(2);

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final java.net.http.HttpClient httpClient;
  private final String apiKey;
  private final String model;
  private final String baseUrl;

  public MistralOcrWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      String apiKey,
      String model,
      String baseUrl) {
    super(jobService, jobEventService, JOB_KIND, workerId);
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.apiKey = apiKey;
    this.model = model;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.httpClient =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected void processJob(Job job) throws Exception {
    Page page =
        pageRepository
            .findById(job.getPageId())
            .orElseThrow(() -> new IllegalStateException("Page not found: " + job.getPageId()));

    Attachment attachment =
        attachmentRepository
            .findById(page.getAttachmentId())
            .orElseThrow(
                () -> new IllegalStateException("Attachment not found: " + page.getAttachmentId()));

    Path imagePath = storageService.getPath(attachment);
    byte[] imageBytes = downsizeIfNeeded(imagePath);
    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

    String ocrText = callOcrApi(base64Image);

    PageText pt = new PageText();
    pt.setPageId(page.getId());
    pt.setEngine("mistral-ocr");
    pt.setTextRaw(ocrText);
    pt.setCreatedAt(Instant.now());
    pageTextRepository.save(pt);

    log.info(
        "Mistral OCR: page={} record={} chars={}",
        page.getId(),
        job.getRecordId(),
        ocrText.length());
  }

  private byte[] downsizeIfNeeded(Path imagePath) throws Exception {
    BufferedImage img = ImageIO.read(imagePath.toFile());
    int w = img.getWidth();
    int h = img.getHeight();
    int longest = Math.max(w, h);
    if (longest <= MAX_IMAGE_DIMENSION) {
      return Files.readAllBytes(imagePath);
    }
    double scale = (double) MAX_IMAGE_DIMENSION / longest;
    int newW = (int) (w * scale);
    int newH = (int) (h * scale);
    BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = resized.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(img, 0, 0, newW, newH, null);
    g.dispose();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(resized, "jpg", baos);
    return baos.toByteArray();
  }

  private String callOcrApi(String base64Image) throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(
            Map.of(
                "model",
                model,
                "document",
                Map.of("type", "image_url", "image_url", "data:image/jpeg;base64," + base64Image),
                "include_image_base64",
                false));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/ocr"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(300))
            .build();

    // The endpoint serves ~32 concurrent requests cleanly and starts returning 429 beyond that.
    // Retrying in-worker is much cheaper than failing the job and waiting for the pipeline audit,
    // which only retries while attempts < 3 and would otherwise poison pages during a burst.
    RuntimeException last = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status == 200) {
        return extractText(objectMapper.readTree(response.body()));
      }

      last =
          new RuntimeException("Mistral OCR API returned HTTP " + status + ": " + response.body());

      boolean retryable = status == 429 || status >= 500;
      if (!retryable || attempt == MAX_ATTEMPTS - 1) {
        throw last;
      }
      Thread.sleep(RETRY_BASE_DELAY.toMillis() * (attempt + 1L));
    }
    throw last;
  }

  /**
   * Joins the markdown of every returned page. A single image yields one page, but the same
   * endpoint accepts multi-page PDFs, so this does not assume a count.
   */
  static String extractText(JsonNode json) {
    StringBuilder sb = new StringBuilder();
    for (JsonNode page : json.path("pages")) {
      String md = page.path("markdown").asText("");
      if (md.isBlank()) {
        continue;
      }
      if (!sb.isEmpty()) {
        sb.append("\n\n");
      }
      sb.append(md);
    }
    if (sb.isEmpty()) {
      throw new RuntimeException("Mistral OCR response contained no page markdown: " + json);
    }
    return sb.toString();
  }
}
