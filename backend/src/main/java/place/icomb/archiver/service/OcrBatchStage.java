package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.repository.PageTextRepository;

/** OCR through the provider's batch API: a page image in, markdown and block coordinates out. */
public class OcrBatchStage implements BatchStage {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OcrBatchStage.class);

  /** Resolves a page to its image on disk. */
  public interface PageImages {
    Path imagePathFor(long pageId);
  }

  private final String model;
  private final PageImages images;
  private final PageTextRepository pageTexts;
  private final JobService jobService;
  private final PipelineStateMachine stateMachine;

  public OcrBatchStage(
      String model,
      PageImages images,
      PageTextRepository pageTexts,
      JobService jobService,
      PipelineStateMachine stateMachine) {
    this.model = model;
    this.images = images;
    this.pageTexts = pageTexts;
    this.jobService = jobService;
    this.stateMachine = stateMachine;
  }

  @Override
  public String jobKind() {
    return "ocr_page_mistral";
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public String endpoint() {
    return "/v1/ocr";
  }

  /** Page images run from 32 kB to 7.7 MB, so a batch is bounded by bytes, not by count. */
  @Override
  public boolean sizedByImageBytes() {
    return true;
  }

  @Override
  public Map<String, Object> buildRequestBody(Job job) throws Exception {
    // Sent as scanned. The provider bills per page rather than per byte, so downscaling would
    // trade transcription fidelity — on exactly the faint, dense material that is hardest to
    // read — for nothing at all.
    byte[] image = Files.readAllBytes(images.imagePathFor(job.getPageId()));
    return Map.of(
        "document",
        Map.of(
            "type",
            "image_url",
            "image_url",
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image)),
        "include_image_base64",
        false);
  }

  @Override
  public void applyResult(Job job, JsonNode body) {
    pageTexts.deleteByPageId(job.getPageId());
    pageTexts.insertOcrResult(
        job.getPageId(),
        "mistral-ocr",
        extractText(body),
        OcrContentType.MARKDOWN,
        body.toString());

    carryPageOnward(job);
  }

  /**
   * Carries a single re-read page through translation, the record's PDF and its embedding.
   *
   * <p>Only for a job that asks for it. A record being OCR'd for the first time enqueues hundreds
   * of these jobs and the state machine advances the record once they have all finished; firing per
   * page there would translate and re-embed the record hundreds of times.
   *
   * <p>{@code andThen} is set by {@code POST /api/admin/reocr-page}, which re-reads one page on a
   * chosen engine. It was honoured only by the Transkribus worker, so a page sent back to this
   * engine was re-transcribed and then left with the translation of the text it had just replaced —
   * record 4006 page 28 showed an English "Unable to translate…" over perfectly good German for
   * that reason.
   */
  private void carryPageOnward(Job job) {
    if (job.getPayload() == null || job.getPageId() == null || job.getRecordId() == null) {
      return;
    }
    try {
      JsonNode payload =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(job.getPayload());
      String andThen = payload.path("andThen").asText("");
      if (andThen.isBlank() || "none".equalsIgnoreCase(andThen)) {
        return;
      }
      stateMachine.advanceSinglePage(job.getRecordId(), job.getPageId());
    } catch (Exception e) {
      // The transcription is saved; failing to queue the follow-on work must not fail the job and
      // have the page read again at cost.
      log.warn(
          "Page {} re-read but could not be carried onward: {}", job.getPageId(), e.getMessage());
    }
  }

  /**
   * Concatenates the markdown the provider returned.
   *
   * <p>Blank output is an empty string, not an error. A blank verso is a correct OCR result, and
   * treating it as a failure cost three billed attempts per blank page before the page was marked
   * failed anyway.
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
}
