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
import java.util.List;
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
 * Internal OCR worker that claims {@code ocr_page_qwen3vl} jobs and processes them via any
 * OpenAI-compatible vision API (DeepInfra, Fireworks, Together AI, Ollama, etc.). Reads page images
 * directly from disk and writes OCR results directly to the DB.
 *
 * <p>Instances are created by {@link place.icomb.archiver.config.WorkerSchedulingConfig}. Scale via
 * {@code archiver.ocr.qwen.concurrency} (each unit = one single-threaded worker instance).
 */
public class QwenOcrWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(QwenOcrWorker.class);
  private static final String JOB_KIND = "ocr_page_qwen3vl";
  private static final int MAX_IMAGE_DIMENSION = 2048;

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final java.net.http.HttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final String model;

  public QwenOcrWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      String baseUrl,
      String apiKey,
      String model) {
    super(jobService, jobEventService, JOB_KIND, workerId);
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.model = model;
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

    String ocrText = callVisionApi(base64Image);

    PageText pt = new PageText();
    pt.setPageId(page.getId());
    pt.setEngine("qwen3vl");
    pt.setTextRaw(ocrText);
    pt.setCreatedAt(Instant.now());
    pageTextRepository.save(pt);

    log.info(
        "Qwen OCR: page={} record={} chars={}", page.getId(), job.getRecordId(), ocrText.length());
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
    log.debug(
        "Resized {}x{} → {}x{} ({}KB → {}KB)",
        w,
        h,
        newW,
        newH,
        Files.size(imagePath) / 1024,
        baos.size() / 1024);
    return baos.toByteArray();
  }

  private String callVisionApi(String base64Image) throws Exception {
    String dataUri = "data:image/jpeg;base64," + base64Image;
    String requestBody =
        objectMapper.writeValueAsString(
            Map.of(
                "model",
                model,
                "messages",
                List.of(
                    Map.of(
                        "role",
                        "user",
                        "content",
                        List.of(
                            Map.of("type", "image_url", "image_url", Map.of("url", dataUri)),
                            Map.of(
                                "type",
                                "text",
                                "text",
                                "Extract all text from this document image. Return only the raw text content, preserving the original layout as much as possible. Do not add any commentary or explanation.")))),
                "max_tokens",
                4096,
                "stream",
                false));

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(600));

    if (!apiKey.isEmpty()) {
      reqBuilder.header("Authorization", "Bearer " + apiKey);
    }

    HttpResponse<String> response =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Vision API returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = objectMapper.readTree(response.body());
    return json.get("choices").get(0).get("message").get("content").asText();
  }
}
