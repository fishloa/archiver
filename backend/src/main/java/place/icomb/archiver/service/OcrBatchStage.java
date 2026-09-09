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

  /** Resolves a page to its image on disk. */
  public interface PageImages {
    Path imagePathFor(long pageId);
  }

  private final String model;
  private final PageImages images;
  private final PageTextRepository pageTexts;
  private final JobService jobService;

  public OcrBatchStage(
      String model, PageImages images, PageTextRepository pageTexts, JobService jobService) {
    this.model = model;
    this.images = images;
    this.pageTexts = pageTexts;
    this.jobService = jobService;
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
