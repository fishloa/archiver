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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

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
  private final PageRepositoryPort pages;
  private final StorageService storageService;
  private final JdbcTemplate jdbc;
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
      PageRepositoryPort pages,
      StorageService storageService,
      JdbcTemplate jdbc,
      String apiKey,
      String model,
      String baseUrl,
      int maxBatchPages,
      long maxBatchBytes,
      int pagesPerMinute) {
    this.workerId = workerId;
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.pages = pages;
    this.storageService = storageService;
    this.jdbc = jdbc;
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
    for (Map<String, Object> b :
        jdbc.queryForList("SELECT * FROM ocr_batch WHERE status = 'submitting' ORDER BY id")) {
      long id = ((Number) b.get("id")).longValue();
      String providerId = findProviderBatchByTag(id);
      if (providerId != null) {
        jdbc.update(
            "UPDATE ocr_batch SET provider_job_id = ?, status = 'submitted', submitted_at = now()"
                + " WHERE id = ?",
            providerId,
            id);
        log.warn("Adopted orphaned batch {} as provider job {}", id, providerId);
        continue;
      }
      Instant created = ((java.sql.Timestamp) b.get("created_at")).toInstant();
      if (created.isBefore(Instant.now().minus(SUBMIT_GRACE))) {
        // The provider never saw these pages, so the attempt should not count against them.
        int released =
            jdbc.update(
                "UPDATE job SET status = 'pending', batch_id = NULL, started_at = NULL,"
                    + " attempts = GREATEST(attempts - 1, 0) WHERE batch_id = ?",
                id);
        jdbc.update(
            "UPDATE ocr_batch SET status = 'failed', error = ? WHERE id = ?",
            "submission never reached the provider",
            id);
        log.warn("Batch {} never reached the provider; released {} pages", id, released);
      }
    }

    // Batches the provider has held past any plausible turnaround.
    for (Map<String, Object> b :
        jdbc.queryForList(
            "SELECT id FROM ocr_batch WHERE status = 'submitted' AND submitted_at < ?",
            java.sql.Timestamp.from(Instant.now().minus(PROVIDER_TIMEOUT)))) {
      long id = ((Number) b.get("id")).longValue();
      failRemainingJobs(id, "batch exceeded provider timeout");
      jdbc.update(
          "UPDATE ocr_batch SET status = 'failed', error = ? WHERE id = ?",
          "exceeded provider timeout",
          id);
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
    int budget = remainingPagesThisMinute();
    if (budget <= 0) return;
    // The byte budget decides how many pages actually go; this only bounds how many rows the
    // sizing query has to consider, and keeps us inside the per-minute page allowance.
    int want = Math.min(maxBatchPages, budget);

    Long batchId =
        jdbc.queryForObject(
            "INSERT INTO ocr_batch (status, page_count) VALUES ('submitting', 0) RETURNING id",
            Long.class);
    if (batchId == null) return;

    // Claim by size, not by count. Page images range from 32 kB to 7.7 MB, so a fixed page
    // count would either overshoot the upload limit or waste most of the budget. attachment.bytes
    // is already known, and base64 inflates by 4/3 plus a little JSON envelope, so the batch can
    // size itself in one statement rather than claiming blindly and releasing the overflow.
    List<Map<String, Object>> claimed =
        jdbc.queryForList(
            """
            UPDATE job SET status = 'claimed', attempts = attempts + 1, started_at = now(),
                           batch_id = ?
            WHERE id IN (
                SELECT id FROM (
                    SELECT j.id,
                           row_number() OVER (ORDER BY j.id) AS rn,
                           sum(ceil(a.bytes * 4.0 / 3.0)::bigint + 256)
                               OVER (ORDER BY j.id ROWS UNBOUNDED PRECEDING) AS running
                    FROM job j
                    JOIN page p ON p.id = j.page_id
                    JOIN attachment a ON a.id = p.attachment_id
                    WHERE j.kind = ? AND j.status = 'pending' AND j.batch_id IS NULL
                    ORDER BY j.id
                    LIMIT ?
                ) sized
                -- rn = 1 always qualifies: a page larger than the whole byte budget would
                -- otherwise exceed it on the first row and never be selected at all, sitting
                -- pending forever and blocking its record from ever completing. An oversized
                -- page gets a batch to itself instead.
                WHERE sized.running <= ? OR sized.rn = 1)
              AND status = 'pending'
            RETURNING id, page_id
            """,
            batchId,
            JOB_KIND,
            want,
            maxBatchBytes);

    if (claimed.isEmpty()) {
      jdbc.update("DELETE FROM ocr_batch WHERE id = ?", batchId);
      return;
    }

    Path jsonl = null;
    try {
      BuildResult built = buildJsonl(claimed, batchId);
      jsonl = built.file();
      if (built.included() == 0) {
        releaseAll(batchId, "no readable page images");
        return;
      }
      jdbc.update("UPDATE ocr_batch SET page_count = ? WHERE id = ?", built.included(), batchId);

      String inputFileId = uploadBatchInput(jsonl);
      jdbc.update("UPDATE ocr_batch SET input_file_id = ? WHERE id = ?", inputFileId, batchId);

      String providerJobId = createProviderBatch(inputFileId, batchId);
      jdbc.update(
          "UPDATE ocr_batch SET provider_job_id = ?, status = 'submitted', submitted_at = now()"
              + " WHERE id = ?",
          providerJobId,
          batchId);

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

  /**
   * Pages we may still hand to the provider this minute.
   *
   * <p>Derived from the table rather than an in-memory counter, so the limit survives restarts and
   * needs no coordination.
   */
  private int remainingPagesThisMinute() {
    Integer submitted =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(page_count), 0) FROM ocr_batch"
                + " WHERE submitted_at > now() - interval '60 seconds'",
            Integer.class);
    return pagesPerMinute - (submitted == null ? 0 : submitted);
  }

  private record BuildResult(Path file, int included) {}

  /**
   * Writes the batch request file.
   *
   * <p>Streamed to disk rather than assembled in memory: a full batch is a few hundred megabytes of
   * base64. Images are sent as scanned — the provider bills per page, not per byte, so downscaling
   * would trade transcription quality for nothing.
   */
  private BuildResult buildJsonl(List<Map<String, Object>> claimed, long batchId)
      throws IOException {
    Path file = Files.createTempFile("ocr-batch-" + batchId + "-", ".jsonl");
    long bytes = 0;
    int included = 0;
    List<Long> excluded = new ArrayList<>();

    try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (Map<String, Object> row : claimed) {
        long jobId = ((Number) row.get("id")).longValue();
        long pageId = ((Number) row.get("page_id")).longValue();
        String line;
        try {
          byte[] image = Files.readAllBytes(pages.imagePathFor(pageId));
          line = jsonlLine(jobId, Base64.getEncoder().encodeToString(image));
        } catch (Exception e) {
          jobService.failJob(jobId, "image unreadable: " + e.getMessage());
          continue;
        }
        long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (included > 0 && bytes + size > maxBatchBytes) {
          excluded.add(jobId);
          continue;
        }
        w.write(line);
        w.newLine();
        bytes += size;
        included++;
      }
    }

    // Anything that did not fit goes straight back on the queue for the next batch.
    for (Long jobId : excluded) {
      jdbc.update(
          "UPDATE job SET status = 'pending', batch_id = NULL, started_at = NULL,"
              + " attempts = GREATEST(attempts - 1, 0) WHERE id = ?",
          jobId);
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

  private void releaseAll(long batchId, String reason) {
    jdbc.update(
        "UPDATE job SET status = 'pending', batch_id = NULL, started_at = NULL WHERE batch_id = ?",
        batchId);
    jdbc.update("UPDATE ocr_batch SET status = 'failed', error = ? WHERE id = ?", reason, batchId);
  }

  // ---------------------------------------------------------------------------
  // Phase C — poll
  // ---------------------------------------------------------------------------

  private void poll() {
    for (Map<String, Object> b :
        jdbc.queryForList(
            "SELECT id, provider_job_id FROM ocr_batch WHERE status = 'submitted'"
                + " AND provider_job_id IS NOT NULL ORDER BY id")) {
      long id = ((Number) b.get("id")).longValue();
      String providerId = (String) b.get("provider_job_id");
      try {
        HttpResponse<String> resp =
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/batch/jobs/" + providerId))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) continue;
        JsonNode n = mapper.readTree(resp.body());
        jdbc.update(
            "UPDATE ocr_batch SET succeeded = ?, failed = ?, output_file_id = ?,"
                + " last_polled_at = now() WHERE id = ?",
            n.path("succeeded_requests").asInt(0),
            n.path("failed_requests").asInt(0),
            n.path("output_file").asText(null),
            id);
      } catch (Exception e) {
        // Left 'submitted'; the provider timeout in reconcile() is the backstop.
        log.warn("Polling batch {} failed", id, e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Phase D — collect
  // ---------------------------------------------------------------------------

  private void collect() {
    for (Map<String, Object> b :
        jdbc.queryForList(
            "SELECT id, output_file_id FROM ocr_batch WHERE status = 'submitted'"
                + " AND output_file_id IS NOT NULL ORDER BY id")) {
      long id = ((Number) b.get("id")).longValue();
      try {
        collectOne(id, (String) b.get("output_file_id"));
      } catch (Exception e) {
        // Left 'submitted' so the same output file is re-read next tick. Re-reading costs
        // nothing: the provider has already billed, and writes are guarded by job status.
        log.error("Collecting batch {} failed", id, e);
      }
    }
  }

  private void collectOne(long batchId, String outputFileId) throws Exception {
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
          // Never drop a line: an unrecognisable id means we cannot account for a page, which
          // is how pages go missing silently. Abort and retry the whole file.
          throw new IllegalStateException("unparseable custom_id in batch output: " + customId);
        }
        applyResult(batchId, jobId, n);
      }
    }

    // Anything absent from the output is failed rather than quietly forgotten.
    failRemainingJobs(batchId, "no result in batch output");

    Integer accounted =
        jdbc.queryForObject(
            "SELECT count(*) FROM job WHERE batch_id = ? AND status IN ('completed','failed')",
            Integer.class,
            batchId);
    Integer expected =
        jdbc.queryForObject(
            "SELECT page_count FROM ocr_batch WHERE id = ?", Integer.class, batchId);
    if (accounted != null && expected != null && accounted >= expected) {
      jdbc.update(
          "UPDATE ocr_batch SET status = 'collected', collected_at = now() WHERE id = ?", batchId);
      log.info("Collected batch {} ({} pages accounted for)", batchId, accounted);
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
    Integer still =
        jdbc.queryForObject(
            "SELECT count(*) FROM job WHERE id = ? AND status = 'claimed' AND batch_id = ?",
            Integer.class,
            jobId,
            batchId);
    if (still == null || still == 0) return;

    Long pageId = jdbc.queryForObject("SELECT page_id FROM job WHERE id = ?", Long.class, jobId);
    if (pageId == null) {
      jobService.failJob(jobId, "job has no page");
      return;
    }

    String text = extractText(body);
    jdbc.update("DELETE FROM page_text WHERE page_id = ?", pageId);
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, content_type, created_at, raw_response)"
            + " VALUES (?, ?, ?, ?, ?, ?::jsonb)",
        pageId,
        "mistral-ocr",
        text,
        OcrContentType.MARKDOWN,
        java.sql.Timestamp.from(Instant.now()),
        body.toString());
    jobService.completeJob(jobId, null);
  }

  /**
   * Joins the markdown of every returned page. A single image yields one page, but the same
   * endpoint accepts multi-page PDFs, so this does not assume a count.
   */
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
    // A blank page is a correct OCR result, not an error. Throwing here failed the job, the
    // audit retried it twice more, and it landed terminally failed — three billed calls for a
    // blank verso, which archival scans are full of. Empty text is returned and stored as
    // empty; the page counts as transcribed.
    return sb.toString();
  }

  private void failRemainingJobs(long batchId, String reason) {
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT id FROM job WHERE batch_id = ? AND status = 'claimed'", batchId)) {
      jobService.failJob(((Number) row.get("id")).longValue(), reason);
    }
  }
}
