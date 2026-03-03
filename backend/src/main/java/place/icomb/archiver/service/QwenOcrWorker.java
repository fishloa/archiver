package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
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
 * Internal OCR worker that claims {@code ocr_page_qwen3vl} jobs and processes them via Ollama's
 * Qwen2.5-VL model. Reads page images directly from disk and writes OCR results directly to the DB,
 * avoiding HTTP round-trips to itself.
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
  private final HttpClient httpClient;
  private final String ollamaUrl;
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
      @Value("${archiver.ocr.qwen.ollama-url}") String ollamaUrl,
      @Value("${archiver.ocr.qwen.model}") String model,
      @Value("${archiver.ocr.qwen.concurrency:1}") int concurrency) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.ollamaUrl = ollamaUrl;
    this.model = model;
    this.concurrency = concurrency;
    this.executor = Executors.newFixedThreadPool(concurrency);
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    log.info(
        "Qwen OCR worker enabled (ollama={}, model={}, concurrency={})",
        ollamaUrl,
        model,
        concurrency);
  }

  @Scheduled(fixedDelayString = "${archiver.ocr.qwen.poll-interval:5000}")
  public void pollAndProcess() {
    // Register each concurrency slot as a separate worker so the dashboard shows all threads
    for (int i = 0; i < concurrency; i++) {
      jobEventService.touchWorker("qwen-ocr-" + i, JOB_KIND);
    }

    // Fill up to concurrency slots
    while (inflight.get() < concurrency) {
      Optional<Job> claimed = jobService.claimJob(JOB_KIND);
      if (claimed.isEmpty()) {
        break;
      }
      Job job = claimed.get();
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
    // Wait for all inflight jobs to finish before the next poll cycle
    awaitInflight();
  }

  private void awaitInflight() {
    while (inflight.get() > 0) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
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

    String ocrText = callOllama(base64Image);

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

  private String callOllama(String base64Image) throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(
            Map.of(
                "model",
                model,
                "prompt",
                "Extract all text from this document image. Return only the raw text content, preserving the original layout as much as possible. Do not add any commentary or explanation.",
                "images",
                List.of(base64Image),
                "stream",
                false));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(ollamaUrl + "/api/generate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(300))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = objectMapper.readTree(response.body());
    return json.get("response").asText();
  }
}
