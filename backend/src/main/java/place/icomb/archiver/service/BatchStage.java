package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import place.icomb.archiver.model.Job;

/**
 * What a pipeline stage must supply to be run through a provider's batch API.
 *
 * <p>Everything else — claiming behind the gate, orphan adoption, the accounting that stops pages
 * disappearing, rate limiting, restart recovery — belongs to {@link BatchOrchestrator} and is
 * inherited rather than reimplemented. A stage only says what to ask for and what to do with the
 * answer.
 */
public interface BatchStage {

  /** Job kind this stage consumes, e.g. {@code translate_page}. */
  String jobKind();

  /** Provider model to run the batch against. */
  String model();

  /** Provider endpoint the batch targets, e.g. {@code /v1/ocr}. */
  String endpoint();

  /**
   * Whether a batch is bounded by the total bytes of its pages' images.
   *
   * <p>True for image stages, where one page can be 7.7 MB and a fixed count would either overshoot
   * the upload limit or waste most of it. False for text stages, where the payload is a few
   * kilobytes and the count is what matters.
   */
  default boolean sizedByImageBytes() {
    return false;
  }

  /**
   * Settles a job that needs no provider call, returning true if it did.
   *
   * <p>Some jobs are legitimately answerable without asking anything: a record whose metadata is
   * already English, a page that is genuinely blank. Without this they had to return null from
   * {@link #buildRequestBody}, which the orchestrator can only read as a failure — so work that had
   * been done correctly was recorded as broken. An implementation that returns true has already
   * stored whatever result is appropriate.
   */
  default boolean resolveLocally(Job job) {
    return false;
  }

  /**
   * The {@code body} of one JSONL request line, or null when the request cannot be built.
   *
   * <p>Null means failure and the orchestrator fails the job. A job that simply needs no provider
   * call belongs in {@link #resolveLocally} instead.
   */
  Map<String, Object> buildRequestBody(Job job) throws Exception;

  /** Stores one successful result. The orchestrator has already checked the job is still ours. */
  void applyResult(Job job, JsonNode responseBody);
}
