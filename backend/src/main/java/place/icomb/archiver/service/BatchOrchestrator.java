package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.ProviderBatch;
import place.icomb.archiver.repository.ProviderBatchRepository;

/**
 * Runs a pipeline stage through a provider's batch API.
 *
 * <p>One request carries hundreds of jobs and no thread waits on the provider, so throughput stops
 * being a function of how many workers we are willing to run. What the stage itself supplies is
 * small — see {@link BatchStage}; everything that was hard to get right lives here and is inherited
 * rather than reimplemented per stage:
 *
 * <ul>
 *   <li>claiming through {@link JobService}, so a paused kind is honoured and cannot be bypassed
 *   <li>adopting an unconfirmed submission by asking the provider, rather than resubmitting and
 *       paying twice
 *   <li>refusing to close a batch until every job is accounted for, so results cannot silently go
 *       missing
 *   <li>all in-flight state in the database, so a restart resumes rather than loses work
 * </ul>
 *
 * <p>One instance per stage, single-threaded: the phases run in sequence, so none of this needs
 * locking.
 */
public class BatchOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(BatchOrchestrator.class);

  /** How long a batch may sit at the provider before we give up on it. */
  private static final Duration PROVIDER_TIMEOUT = Duration.ofHours(24);

  /**
   * Grace before an unconfirmed submission is presumed never to have reached the provider. Shorter
   * risks releasing pages the provider has actually accepted, and billing them twice.
   */
  private static final Duration SUBMIT_GRACE = Duration.ofMinutes(2);

  private final String workerId;
  private final BatchStage stage;
  private final MistralBatchClient client;
  private final JobService jobService;
  private final JobEventService jobEventService;
  private final RecordEventService recordEventService;
  private final ProviderBatchRepository batches;
  private final ObjectMapper mapper = new ObjectMapper();

  private final int maxBatchRows;
  private final long maxBatchBytes;
  private final int itemsPerMinute;

  public BatchOrchestrator(
      String workerId,
      BatchStage stage,
      MistralBatchClient client,
      JobService jobService,
      JobEventService jobEventService,
      RecordEventService recordEventService,
      ProviderBatchRepository batches,
      int maxBatchRows,
      long maxBatchBytes,
      int itemsPerMinute) {
    this.workerId = workerId;
    this.stage = stage;
    this.client = client;
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.recordEventService = recordEventService;
    this.batches = batches;
    this.maxBatchRows = maxBatchRows;
    this.maxBatchBytes = maxBatchBytes;
    this.itemsPerMinute = itemsPerMinute;
  }

  /** One pass through all four phases. Reconcile before submitting more. */
  public void tick() {
    jobEventService.touchWorker(workerId, stage.jobKind(), stage.model(), "batch");
    try {
      reconcile();
      collect();
      poll();
      submit();
    } catch (Exception e) {
      log.error("{} tick failed", workerId, e);
    }
  }

  // --- Phase A: resolve submissions whose outcome we never recorded -------------------

  private void reconcile() {
    for (ProviderBatch b : batches.findByStatus(ProviderBatch.SUBMITTING, stage.jobKind())) {
      String providerId = client.findByTag(b.getId());
      if (providerId != null) {
        batches.markSubmitted(b.getId(), providerId);
        log.warn("Adopted orphaned batch {} as provider job {}", b.getId(), providerId);
        continue;
      }
      if (b.getCreatedAt() != null
          && b.getCreatedAt().isBefore(Instant.now().minus(SUBMIT_GRACE))) {
        // The provider never saw this work, so the attempt should not count against it.
        int released = jobService.releaseBatch(b.getId(), true);
        batches.markFailed(b.getId(), "submission never reached the provider");
        log.warn("Batch {} never reached the provider; released {} jobs", b.getId(), released);
      }
    }

    for (ProviderBatch b :
        batches.findTimedOut(Instant.now().minus(PROVIDER_TIMEOUT), stage.jobKind())) {
      failRemaining(b.getId(), "batch exceeded provider timeout");
      batches.markFailed(b.getId(), "exceeded provider timeout");
    }
  }

  // --- Phase B: submit ---------------------------------------------------------------

  private void submit() {
    int budget = itemsPerMinute - batches.pagesSubmittedInLastMinute(stage.jobKind());
    if (budget <= 0) return;
    int want = Math.min(maxBatchRows, budget);

    Long batchId = batches.createSubmitting(stage.jobKind());
    if (batchId == null) return;

    List<Job> claimed =
        stage.sizedByImageBytes()
            ? jobService.claimBatch(stage.jobKind(), want, maxBatchBytes, batchId)
            : jobService.claimBatchByCount(stage.jobKind(), want, batchId);
    if (claimed.isEmpty()) {
      batches.deleteEmpty(batchId);
      return;
    }

    Path jsonl = null;
    try {
      Built built = writeRequests(claimed, batchId);
      jsonl = built.file();
      if (built.included() == 0) {
        jobService.releaseBatch(batchId, true);
        batches.markFailed(batchId, "nothing could be prepared for submission");
        return;
      }

      String inputFileId = client.uploadInput(jsonl);
      batches.recordInput(batchId, inputFileId, built.included());

      String providerJobId =
          client.createBatch(inputFileId, stage.model(), stage.endpoint(), batchId);
      batches.markSubmitted(batchId, providerJobId);

      recordEventService.pipelineChanged(stage.jobKind(), "batch_submitted");
      log.info(
          "{} submitted batch {} ({} items) as provider job {}",
          workerId,
          batchId,
          built.included(),
          providerJobId);
    } catch (Exception e) {
      log.error("{} failed to submit batch {}", workerId, batchId, e);
      // Left 'submitting'; reconcile() decides whether the provider took it.
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

  private record Built(Path file, int included) {}

  /**
   * Writes the batch request file.
   *
   * <p>Streamed to disk: an image batch is hundreds of megabytes of base64 and must never be
   * assembled in heap. {@code custom_id} is the job id rather than the page id, because a page
   * acquires a new job row on every reset and it is the job row that collection must settle.
   */
  private Built writeRequests(List<Job> claimed, long batchId) throws IOException {
    Path file = Files.createTempFile("batch-" + stage.jobKind() + "-" + batchId + "-", ".jsonl");
    long bytes = 0;
    int included = 0;

    try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (Job job : claimed) {
        String line;
        try {
          if (stage.resolveLocally(job)) {
            jobService.completeJob(job.getId(), null);
            continue;
          }
          Map<String, Object> body = stage.buildRequestBody(job);
          if (body == null) {
            jobService.failJob(job.getId(), "stage could not prepare a request");
            continue;
          }
          line =
              mapper.writeValueAsString(
                  Map.of("custom_id", String.valueOf(job.getId()), "body", body));
        } catch (Exception e) {
          jobService.failJob(job.getId(), "request build failed: " + e.getMessage());
          continue;
        }
        long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (included > 0 && bytes + size > maxBatchBytes) {
          // Whatever does not fit waits for the next batch rather than being carried.
          jobService.releaseJob(job.getId(), true);
          continue;
        }
        w.write(line);
        w.newLine();
        bytes += size;
        included++;
      }
    }
    return new Built(file, included);
  }

  // --- Phase C: poll -----------------------------------------------------------------

  private void poll() {
    for (ProviderBatch b : batches.findPollable(stage.jobKind())) {
      JsonNode n = client.pollBatch(b.getProviderJobId());
      if (n == null) continue; // left submitted; the provider timeout is the backstop
      batches.recordPoll(
          b.getId(),
          n.path("succeeded_requests").asInt(0),
          n.path("failed_requests").asInt(0),
          n.path("output_file").asText(null));
      // A poll moves no job row, so without this the dashboard cannot tell that anything is
      // happening while the provider works.
      recordEventService.pipelineChanged(stage.jobKind(), "batch_polled");
    }
  }

  // --- Phase D: collect --------------------------------------------------------------

  private void collect() {
    for (ProviderBatch b : batches.findCollectable(stage.jobKind())) {
      try {
        collectOne(b.getId(), b.getOutputFileId(), b.getPageCount());
      } catch (Exception e) {
        // Left 'submitted' so the same output is re-read next tick. Re-reading costs nothing:
        // the provider has already billed, and every write is guarded by job status.
        log.error("Collecting batch {} failed", b.getId(), e);
      }
    }
  }

  private void collectOne(long batchId, String outputFileId, int expected) throws Exception {
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                client.downloadOutput(outputFileId), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode n = mapper.readTree(line);
        String customId = n.path("custom_id").asText(null);
        long jobId;
        try {
          jobId = Long.parseLong(customId);
        } catch (Exception e) {
          // Never drop a line: an unreadable id means an item cannot be accounted for, which is
          // exactly how work disappears silently. Abort and retry the whole file.
          throw new IllegalStateException("unparseable custom_id in batch output: " + customId);
        }
        applyOne(batchId, jobId, n);
      }
    }

    // Anything absent from the output is failed rather than quietly forgotten.
    failRemaining(batchId, "no result in batch output");

    if (jobService.countSettledInBatch(batchId) >= expected) {
      batches.markCollected(batchId);
      recordEventService.pipelineChanged(stage.jobKind(), "batch_collected");
      log.info("Collected batch {} ({} items)", batchId, expected);
    }
  }

  private void applyOne(long batchId, long jobId, JsonNode line) {
    int status = line.path("response").path("status_code").asInt(0);
    if (status != 200) {
      jobService.failJob(jobId, "batch item failed: HTTP " + status);
      return;
    }
    // Guarded so re-reading the same output cannot write twice.
    if (!jobService.isStillClaimedBy(jobId, batchId)) return;

    Job job = jobService.findById(jobId).orElse(null);
    if (job == null) {
      return;
    }
    try {
      stage.applyResult(job, line.path("response").path("body"));
      jobService.completeJob(jobId, null);
    } catch (Exception e) {
      jobService.failJob(jobId, "result apply failed: " + e.getMessage());
    }
  }

  private void failRemaining(long batchId, String reason) {
    for (Job job : jobService.findClaimedInBatch(batchId)) {
      jobService.failJob(job.getId(), reason);
    }
  }
}
