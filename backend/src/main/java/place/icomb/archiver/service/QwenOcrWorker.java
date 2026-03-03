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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;

/**
 * Internal OCR worker that claims {@code ocr_page_qwen3vl} jobs and processes them via Ollama's
 * Qwen2.5-VL model. Reads page images directly from disk and writes OCR results directly to the DB,
 * avoiding HTTP round-trips to itself.
 *
 * <p>Disabled by default ({@code archiver.ocr.qwen.enabled=false}).
 */
@Service
@ConditionalOnProperty(name = "archiver.ocr.qwen.enabled", havingValue = "true")
public class QwenOcrWorker {

  private static final Logger log = LoggerFactory.getLogger(QwenOcrWorker.class);
  private static final String JOB_KIND = "ocr_page_qwen3vl";

  private final JobService jobService;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient;
  private final String ollamaUrl;
  private final String model;

  public QwenOcrWorker(
      JobService jobService,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      JdbcTemplate jdbcTemplate,
      @Value("${archiver.ocr.qwen.ollama-url}") String ollamaUrl,
      @Value("${archiver.ocr.qwen.model}") String model) {
    this.jobService = jobService;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.jdbcTemplate = jdbcTemplate;
    this.ollamaUrl = ollamaUrl;
    this.model = model;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    log.info("Qwen OCR worker enabled (ollama={}, model={})", ollamaUrl, model);
  }

  @Scheduled(fixedDelayString = "${archiver.ocr.qwen.poll-interval:5000}")
  public void pollAndProcess() {
    Optional<Job> claimed = jobService.claimJob(JOB_KIND);
    if (claimed.isEmpty()) {
      return;
    }
    Job job = claimed.get();
    long start = System.currentTimeMillis();
    try {
      processJob(job);
      long elapsed = System.currentTimeMillis() - start;
      log.info(
          "Qwen OCR completed: job={} page={} record={} ({}ms)",
          job.getId(),
          job.getPageId(),
          job.getRecordId(),
          elapsed);
    } catch (Exception e) {
      log.error(
          "Qwen OCR failed: job={} page={} record={}: {}",
          job.getId(),
          job.getPageId(),
          job.getRecordId(),
          e.getMessage(),
          e);
      jobService.failJob(job.getId(), e.getMessage());
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

    jdbcTemplate.update(
        "INSERT INTO page_text (page_id, engine, confidence, text_raw, created_at) VALUES (?, 'qwen3vl', NULL, ?, now())",
        page.getId(),
        ocrText);

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
