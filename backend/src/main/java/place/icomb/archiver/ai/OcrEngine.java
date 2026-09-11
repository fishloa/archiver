package place.icomb.archiver.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Transcribes a scanned page. */
public interface OcrEngine extends AiImplementation {

  @Override
  default AiCapability capability() {
    return AiCapability.OCR;
  }

  /** Provider path a batch of these requests is submitted to, e.g. {@code /v1/ocr}. */
  String endpointPath();

  /**
   * The request body for one page.
   *
   * @param imageBase64 the page image, base64 encoded
   * @param mimeType the image's media type
   * @param lang ISO 639-1 content language, or null
   */
  Map<String, Object> request(String imageBase64, String mimeType, String lang);

  /** What one response body says the page contains. */
  Transcription parse(JsonNode responseBody);

  /**
   * A page's transcription.
   *
   * @param text the transcription exactly as the engine produced it
   * @param contentType {@code text/markdown} or {@code text/plain} — recorded, never sniffed,
   *     because a typescript's centred "- 5 -" is byte-identical to a markdown bullet
   * @param raw the engine's full response, kept for the figure boxes and the invisible text layer
   */
  record Transcription(String text, String contentType, JsonNode raw) {}
}
