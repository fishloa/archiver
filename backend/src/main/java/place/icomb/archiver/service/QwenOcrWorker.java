package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
 * <p>Configure with {@code archiver.ocr.qwen.base-url} (e.g. {@code
 * https://api.deepinfra.com/v1/openai}) and optional {@code archiver.ocr.qwen.api-key}. For Ollama,
 * use {@code http://host:11434/v1} with no API key.
 *
 * <p>Supports concurrent processing via {@code archiver.ocr.qwen.concurrency} (default 1).
 *
 * <p>Disabled by default ({@code archiver.ocr.qwen.enabled=false}).
 */
@Service
@ConditionalOnProperty(name = "archiver.ocr.qwen.enabled", havingValue = "true")
public class QwenOcrWorker {

  private static final Logger log = LoggerFactory.getLogger(QwenOcrWorker.class);
  private static final String JOB_KIND = "ocr_page_qwen3vl";

  private final JobService jobService;
  private final JobEventService jobEventService;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final java.net.http.HttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int concurrency;
  private final ExecutorService executor;
  private final AtomicInteger inflight = new AtomicInteger(0);

  public QwenOcrWorker(
      JobService jobService,
      JobEventService jobEventService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      @Value("${archiver.ocr.qwen.base-url}") String baseUrl,
      @Value("${archiver.ocr.qwen.api-key:}") String apiKey,
      @Value("${archiver.ocr.qwen.model}") String model,
      @Value("${archiver.ocr.qwen.concurrency:1}") int concurrency) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.concurrency = concurrency;
    this.executor = Executors.newFixedThreadPool(concurrency);
    this.httpClient =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    log.info(
        "Qwen OCR worker enabled (base-url={}, model={}, concurrency={}, auth={})",
        baseUrl,
        model,
        concurrency,
        apiKey.isEmpty() ? "none" : "api-key");
  }

  @Scheduled(fixedDelayString = "${archiver.ocr.qwen.poll-interval:5000}")
  public void pollAndProcess() {
    // Register workers so the dashboard shows them
    for (int i = 0; i < concurrency; i++) {
      jobEventService.touchWorker("qwen-ocr-" + i, JOB_KIND);
    }

    // Continuously fill slots as they free up, rather than waiting for the whole batch
    long lastTouch = System.currentTimeMillis();
    boolean exhausted = false;
    while (!exhausted) {
      // Top up to concurrency
      while (inflight.get() < concurrency) {
        Optional<Job> claimed = jobService.claimJob(JOB_KIND);
        if (claimed.isEmpty()) {
          exhausted = true;
          break;
        }
        submitJob(claimed.get());
      }
      if (exhausted || inflight.get() == 0) break;

      // Wait briefly for a slot to free up, then refill
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }

      // Re-touch workers every 30s so they don't expire from the dashboard
      if (System.currentTimeMillis() - lastTouch > 30_000) {
        for (int i = 0; i < concurrency; i++) {
          jobEventService.touchWorker("qwen-ocr-" + i, JOB_KIND);
        }
        lastTouch = System.currentTimeMillis();
      }
    }

    // Wait for remaining inflight jobs before next scheduled poll
    while (inflight.get() > 0) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void submitJob(Job job) {
    inflight.incrementAndGet();
    executor.submit(
        () -> {
          long start = System.currentTimeMillis();
          try {
            processJob(job);
            long elapsed = System.currentTimeMillis() - start;
            log.info(
                "Qwen OCR completed: job={} page={} record={} ({}ms) [inflight={}]",
                job.getId(),
                job.getPageId(),
                job.getRecordId(),
                elapsed,
                inflight.get());
          } catch (Exception e) {
            log.error(
                "Qwen OCR failed: job={} page={} record={}: {}",
                job.getId(),
                job.getPageId(),
                job.getRecordId(),
                e.getMessage(),
                e);
            jobService.failJob(job.getId(), e.getMessage());
          } finally {
            inflight.decrementAndGet();
          }
        });
  }

  private void processJob(Job job) throws Exception {
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
    byte[] imageBytes = Files.readAllBytes(imagePath);
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

    jobService.completeJob(job.getId(), null);
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
            .timeout(Duration.ofSeconds(300));

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
