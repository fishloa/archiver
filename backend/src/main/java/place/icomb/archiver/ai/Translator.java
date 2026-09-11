package place.icomb.archiver.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Translates text into English.
 *
 * <p>The request and the reading of its answer live together deliberately. They were previously
 * apart — one class built the batch line, another parsed it — and they drifted: the model wrapped
 * 13% of the archive in a code fence and opened 5,454 pages with "Here is the translation:",
 * neither of which the parser expected. A provider's quirks belong with the provider.
 */
public interface Translator extends AiImplementation {

  @Override
  default AiCapability capability() {
    return AiCapability.TRANSLATION;
  }

  /** Provider path a batch of these requests is submitted to, e.g. {@code /v1/chat/completions}. */
  String endpointPath();

  /**
   * The request body for one item.
   *
   * @param text the source text
   * @param sourceLang ISO 639-1 code, or null when unknown — the model handles any source
   */
  Map<String, Object> request(String text, String sourceLang);

  /** The translation carried by one response body, cleaned of anything the model added. */
  String parse(JsonNode responseBody);
}
