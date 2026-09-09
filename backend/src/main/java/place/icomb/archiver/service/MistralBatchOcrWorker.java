package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.OcrBatch;
import place.icomb.archiver.repository.OcrBatchRepository;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * OCR via Mistral's Batch API, which is the only path this worker uses.
 *
 * <p>The interactive endpoint it replaced held a scheduler thread for the whole HTTP round trip, so
 * throughput was governed by how many threads we were willing to burn rather than by the provider's
 * 1,250 pages/minute allowance. Batching decouples the two: one request carries hundreds of pages,
 * and no thread waits on OCR.
 *
 * <p><b>Batches know nothing about records.</b> A batch is a flat set of un-OCR'd pages taken off
 * the queue in whatever order they come. Record completion needs no handling here: each page keeps
 * its own {@code job} row carrying {@code record_id}, so when the last page of a record lands,
 * {@link JobService#completeJob} runs the state machine and the record advances. The assembly is
 * already someone else's job.
 *
 * <p>All in-flight state lives in {@code ocr_batch}, never in memory, so a restart resumes from the
 * table. A single instance runs the four phases in sequence, which is why none of this needs
 * locking.
 */
public class MistralBatchOcrWorker {

  private static final Logger log = LoggerFactory.getLogger(MistralBatchOcrWorker.class);

  static final String JOB_KIND = "ocr_page_mistral";

  /** How long a batch may sit at the provider before we give up on it. */
  private static final Duration PROVIDER_TIMEOUT = Duration.ofHours(24);

  /**
   * Grace before an unconfirmed submission is presumed never to have reached the provider. Shorter
   * than this risks releasing pages the provider has actually accepted, and billing them twice.
   */
  private static final Duration SUBMIT_GRACE = Duration.ofMinutes(2);

  private final String workerId;
  private final JobService jobService;
  private final JobEventService jobEventService;
  private final RecordEventService recordEventService;
  private final PageRepositoryPort pages;
  private final StorageService storageService;
  private final OcrBatchRepository batches;
  private final PageTextRepository pageTexts;
  private final ObjectMapper mapper = new ObjectMapper();
  private final java.net.http.HttpClient http;

  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final int maxBatchPages;
  private final long maxBatchBytes;
  private final int pagesPerMinute;

  /** Narrow view of what this worker needs to resolve a page to its image on disk. */
  public interface PageRepositoryPort {
    Path imagePathFor(long pageId);
  }

  public MistralBatchOcrWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      RecordEventService recordEventService,
      PageRepositoryPort pages,
      StorageService storageService,
      OcrBatchRepository batches,
      PageTextRepository pageTexts,
      String apiKey,
      String model,
      String baseUrl,
      int maxBatchPages,
      long maxBatchBytes,
      int pagesPerMinute) {
    this.workerId = workerId;
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.recordEventService = recordEventService;
    this.pages = pages;
    this.storageService = storageService;
    this.batches = batches;
    this.pageTexts = pageTexts;
    this.apiKey = apiKey;
    this.model = model;
    this.baseUrl = baseUrl;
    this.maxBatchPages = maxBatchPages;
    this.maxBatchBytes = maxBatchBytes;
    this.pagesPerMinute = pagesPerMinute;
    this.http =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  /** One pass through all four phases. Ordering matters: reconcile before submitting more. */
  public void tick() {
    jobEventService.touchWorker(workerId, JOB_KIND, model, baseUrl);
    try {
      reconcile();
      collect();
      poll();
      submit();
    } catch (Exception e) {
      log.error("{} tick failed", workerId, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Phase A — reconcile submissions whose outcome we never recorded
  // ---------------------------------------------------------------------------

  /**
   * Resolves batches left in {@code submitting} by a crash between claiming pages and recording the
   * provider's job id.
   *
   * <p>The pages are already claimed, so doing nothing would strand them. Releasing them blindly
   * would resubmit work the provider may already be billing us for. So we ask the provider first,
   * matching on the batch id we tag every submission with, and only release when it genuinely never
   * arrived.
   */
  private void reconcile() {
    for (OcrBatch b : batches.findByStatus(OcrBatch.SUBMITTING)) {
      String providerId = findProviderBatchByTag(b.getId());
      if (providerId != null) {
        batches.markSubmitted(b.getId(), providerId);
        log.warn("Adopted orphaned batch {} as provider job {}", b.getId(), providerId);
        continue;
      }
      if (b.getCreatedAt() != null
          && b.getCreatedAt().isBefore(Instant.now().minus(SUBMIT_GRACE))) {
        // The provider never saw these pages, so the attempt should not count against them.
        int released = jobService.releaseBatch(b.getId(), true);
        batches.markFailed(b.getId(), "submission never reached the provider");
        log.warn("Batch {} never reached the provider; released {} pages", b.getId(), released);
      }
    }

    for (OcrBatch b : batches.findTimedOut(Instant.now().minus(PROVIDER_TIMEOUT))) {
      failRemainingJobs(b.getId(), "batch exceeded provider timeout");
      batches.markFailed(b.getId(), "exceeded provider timeout");
    }
  }

  /**
   * Finds a provider batch carrying our tag.
   *
   * <p>Scans recent batches and matches client-side: the provider returns metadata in listings but
   * rejects it as a query filter, so filtering server-side is not available.
   */
  private String findProviderBatchByTag(long batchId) {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/v1/batch/jobs?page_size=100"))
                  .header("Authorization", "Bearer " + apiKey)
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) return null;
      for (JsonNode n : mapper.readTree(resp.body()).path("data")) {
        if (String.valueOf(batchId).equals(n.path("metadata").path("ocr_batch_id").asText(null))) {
          return n.path("id").asText(null);
        }
      }
    } catch (Exception e) {
      log.warn("Could not list provider batches while reconciling batch {}", batchId, e);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Phase B — submit
  // ---------------------------------------------------------------------------

  private void submit() {
    int budget = pagesPerMinute - batches.pagesSubmittedInLastMinute();
    if (budget <= 0) return;
    // The byte budget decides how many pages actually go; this only bounds how many rows the
    // sizing query considers, and keeps us inside the per-minute page allowance.
    int want = Math.min(maxBatchPages, budget);

    Long batchId = batches.createSubmitting();
    if (batchId == null) return;

    // Claiming goes through JobService so it passes the same pause gate as every other worker.
    List<Job> claimed = jobService.claimBatch(JOB_KIND, want, maxBatchBytes, batchId);
    if (claimed.isEmpty()) {
      batches.deleteEmpty(batchId);
      return;
    }

    Path jsonl = null;
    try {
      BuildResult built = buildJsonl(claimed, batchId);
      jsonl = built.file();
      if (built.included() == 0) {
        jobService.releaseBatch(batchId, true);
        batches.markFailed(batchId, "no readable page images");
        return;
      }

      String inputFileId = uploadBatchInput(jsonl);
      batches.recordInput(batchId, inputFileId, built.included());

      String providerJobId = createProviderBatch(inputFileId, batchId);
      batches.markSubmitted(batchId, providerJobId);

      recordEventService.pipelineChanged(JOB_KIND, "batch_submitted");
      log.info(
          "{} submitted batch {} ({} pages) as provider job {}",
          workerId,
          batchId,
          built.included(),
          providerJobId);
    } catch (Exception e) {
      log.error("{} failed to submit batch {}", workerId, batchId, e);
      // Left in 'submitting'; reconcile() decides whether the provider took it.
    } finally {
      if (jsonl != null) {
        try {
          Files.deleteIfExists(jsonl);
        } catch (IOException ignored) {
          // Temp file cleanup only.
        }
      }
    }
  }

  private record BuildResult(Path file, int included) {}

  /**
   * Writes the batch request file.
   *
   * <p>Streamed to disk rather than assembled in memory: a full batch is a few hundred megabytes of
   * base64. Images are sent as scanned — the provider bills per page, not per byte, so downscaling
   * would trade transcription quality for nothing.
   */
  private BuildResult buildJsonl(List<Job> claimed, long batchId) throws IOException {
    Path file = Files.createTempFile("ocr-batch-" + batchId + "-", ".jsonl");
    long bytes = 0;
    int included = 0;

    try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (Job job : claimed) {
        String line;
        try {
          byte[] image = Files.readAllBytes(pages.imagePathFor(job.getPageId()));
          line = jsonlLine(job.getId(), Base64.getEncoder().encodeToString(image));
        } catch (Exception e) {
          jobService.failJob(job.getId(), "image unreadable: " + e.getMessage());
          continue;
        }
        long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (included > 0 && bytes + size > maxBatchBytes) {
          // Anything that does not fit is left for the next batch rather than carried.
          jobService.releaseJob(job.getId(), true);
          continue;
        }
        w.write(line);
        w.newLine();
        bytes += size;
        included++;
      }
    }
    return new BuildResult(file, included);
  }

  /** custom_id is the job id, not the page id: a page gets a fresh job row on every reset. */
  private String jsonlLine(long jobId, String base64Image) throws IOException {
    return mapper.writeValueAsString(
        Map.of(
            "custom_id",
            String.valueOf(jobId),
            "body",
            Map.of(
                "document",
                Map.of("type", "image_url", "image_url", "data:image/jpeg;base64," + base64Image),
                "include_image_base64",
                false)));
  }

  /** Hand-rolled multipart: the JDK HTTP client has no multipart support. Streamed from disk. */
  private String uploadBatchInput(Path jsonl) throws Exception {
    String boundary = "----archiver" + System.nanoTime();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n"
                + "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"batch.jsonl\"\r\nContent-Type: application/jsonl\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/files"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofMinutes(30))
            .POST(
                HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(head),
                    HttpRequest.BodyPublishers.ofFile(jsonl),
                    HttpRequest.BodyPublishers.ofByteArray(tail)))
            .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "file upload failed: " + resp.statusCode() + " " + resp.body());
    }
    return mapper.readTree(resp.body()).path("id").asText();
  }

  private String createProviderBatch(String inputFileId, long batchId) throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "input_files",
                List.of(inputFileId),
                "model",
                model,
                "endpoint",
                "/v1/ocr",
                // Tagged so an unconfirmed submission can be identified rather than re-billed.
                "metadata",
                Map.of("ocr_batch_id", String.valueOf(batchId))));
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/batch/jobs"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "batch create failed: " + resp.statusCode() + " " + resp.body());
    }
    return mapper.readTree(resp.body()).path("id").asText();
  }

  // ---------------------------------------------------------------------------
  // Phase C — poll
  // ---------------------------------------------------------------------------

  private void poll() {
    for (OcrBatch b : batches.findPollable()) {
      try {
        HttpResponse<String> resp =
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/batch/jobs/" + b.getProviderJobId()))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) continue;
        JsonNode n = mapper.readTree(resp.body());
        batches.recordPoll(
            b.getId(),
            n.path("succeeded_requests").asInt(0),
            n.path("failed_requests").asInt(0),
            n.path("output_file").asText(null));
        // A poll changes only batch counters, never a job row, so the dashboard would not
        // otherwise learn that anything is happening while the provider works.
        recordEventService.pipelineChanged(JOB_KIND, "batch_polled");
      } catch (Exception e) {
        // Left 'submitted'; the provider timeout in reconcile() is the backstop.
        log.warn("Polling batch {} failed", b.getId(), e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Phase D — collect
  // ---------------------------------------------------------------------------

  private void collect() {
    for (OcrBatch b : batches.findCollectable()) {
      try {
        collectOne(b.getId(), b.getOutputFileId(), b.getPageCount());
      } catch (Exception e) {
        // Left 'submitted' so the same output file is re-read next tick. Re-reading costs
        // nothing: the provider has already billed, and writes are guarded by job status.
        log.error("Collecting batch {} failed", b.getId(), e);
      }
    }
  }

  private void collectOne(long batchId, String outputFileId, int pageCount) throws Exception {
    HttpResponse<java.io.InputStream> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/files/" + outputFileId + "/content"))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException("output download failed: " + resp.statusCode());
    }

    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode n = mapper.readTree(line);
        String customId = n.path("custom_id").asText(null);
        long jobId;
        try {
          jobId = Long.parseLong(customId);
        } catch (Exception e) {
          // Never drop a line: an unrecognisable id means a page cannot be accounted for, which
          // is exactly how pages go missing silently. Abort and retry the whole file.
          throw new IllegalStateException("unparseable custom_id in batch output: " + customId);
        }
        applyResult(batchId, jobId, n);
      }
    }

    // Anything absent from the output is failed rather than quietly forgotten.
    failRemainingJobs(batchId, "no result in batch output");

    if (jobService.countSettledInBatch(batchId) >= pageCount) {
      batches.markCollected(batchId);
      recordEventService.pipelineChanged(JOB_KIND, "batch_collected");
      log.info("Collected batch {} ({} pages)", batchId, pageCount);
    }
  }

  private void applyResult(long batchId, long jobId, JsonNode line) {
    int status = line.path("response").path("status_code").asInt(0);
    JsonNode body = line.path("response").path("body");
    if (status != 200) {
      jobService.failJob(jobId, "batch page failed: HTTP " + status);
      return;
    }
    // Guarded so a re-read of the same output file cannot write twice.
    if (!jobService.isStillClaimedBy(jobId, batchId)) return;

    Long pageId = jobService.pageIdOf(jobId);
    if (pageId == null) {
      jobService.failJob(jobId, "job has no page");
      return;
    }

    pageTexts.deleteByPageId(pageId);
    pageTexts.insertOcrResult(
        pageId, "mistral-ocr", extractText(body), OcrContentType.MARKDOWN, body.toString());
    jobService.completeJob(jobId, null);
  }

  /**
   * Concatenates the markdown the provider returned for a document.
   *
   * <p>Blank output is returned as an empty string rather than raised as an error. A blank verso is
   * a correct OCR result, and treating it as a failure cost three billed attempts per blank page
   * before the page was marked failed anyway.
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
    return sb.toString();
  }

  private void failRemainingJobs(long batchId, String reason) {
    for (Job job : jobService.findClaimedInBatch(batchId)) {
      jobService.failJob(job.getId(), reason);
    }
  }
}
