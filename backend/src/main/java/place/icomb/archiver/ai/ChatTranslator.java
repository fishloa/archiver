package place.icomb.archiver.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translation through a chat completions endpoint.
 *
 * <p>Provider-agnostic: the request is the OpenAI chat shape and the answer is read from {@code
 * choices[0].message.content}, which Mistral, DeepInfra, vLLM, Ollama and OpenAI all speak. What
 * differs between them is not the request but how it is submitted — Mistral's asynchronous batch
 * job, or one HTTP call per document — and that is chosen by {@link BatchStyle} when the worker is
 * wired, not here.
 *
 * <p>The instruction and the cleanup of the answer live together because they are two halves of the
 * same knowledge about this model. Kept apart, they drifted: the model wrapped 13% of the archive
 * in a code fence and opened 5,454 pages with "Here is the translation:", and the code reading the
 * answers expected neither.
 */
public class ChatTranslator implements Translator {

  /**
   * Names, dates and reference numbers are evidence in these documents, so the instruction leads
   * with preserving them. A model that renders "Führungszeugnis" as "Leadership Certificate" —
   * which one candidate did — is worse than no translation, because it reads as authoritative.
   */
  private static String instruction(String targetLang) {
    return "Translate the following German or Czech archival document text into "
        + Locale.of(targetLang).getDisplayLanguage(Locale.ENGLISH)
        + ". Preserve all proper names, place names, dates, reference numbers and titles exactly "
        + "as written. Keep the markdown structure. Do not add commentary or explanation. "
        + "Output only the translation.\n\n";
  }

  private static final String LANGUAGE = "((?:english|czech|german)\\s+)?";
  private static final String TRANSLATION_WORD = "(?:translations?|překlad|[üÜ]bersetzung)";

  private final AiRegistry.Registration registration;
  private final String apiKey;

  public ChatTranslator(AiRegistry.Registration registration, String apiKey) {
    this.registration = registration;
    this.apiKey = apiKey;
  }

  @Override
  public String id() {
    return registration.id();
  }

  @Override
  public String provider() {
    return registration.provider();
  }

  @Override
  public String model() {
    return registration.model();
  }

  @Override
  public int maxBatchSize() {
    return registration.maxBatchSize();
  }

  @Override
  public boolean isConfigured() {
    return !apiKey.isBlank();
  }

  @Override
  public String endpoint() {
    return registration.baseUrl();
  }

  @Override
  public String endpointPath() {
    return registration.endpointPath() == null
        ? "/v1/chat/completions"
        : registration.endpointPath();
  }

  @Override
  public Map<String, Object> request(String text, String sourceLang, String targetLang) {
    return Map.of(
        "max_tokens",
        4000,
        "temperature",
        0.1,
        "messages",
        List.of(Map.of("role", "user", "content", instruction(targetLang) + text)));
  }

  @Override
  public String parse(JsonNode responseBody) {
    return parse(responseBody, null);
  }

  @Override
  public String parse(JsonNode responseBody, String sourceText) {
    String content =
        responseBody.path("choices").path(0).path("message").path("content").asText("").strip();
    return stripPreamble(unwrapCodeFence(stripPreamble(content, sourceText)), sourceText);
  }

  /**
   * Removes a code fence wrapping the whole translation.
   *
   * <p>The model is told to output only the translation and mostly does, but wrapped 13% of this
   * archive in ```markdown regardless. Stored that way the page renders as a literal block of pipes
   * and hashes instead of a table — the reader sees the markup rather than the document.
   *
   * <p>Only an enclosing fence is removed. A fence in the middle of a page is content.
   */
  public static String unwrapCodeFence(String text) {
    if (text == null) return "";
    String t = text.strip();
    if (!t.startsWith("```")) return t;

    int firstNewline = t.indexOf('\n');
    if (firstNewline < 0) return t;
    String opener = t.substring(3, firstNewline).strip();
    if (!opener.isEmpty() && !opener.matches("[A-Za-z0-9_+-]+")) return t;

    String body = t.substring(firstNewline + 1);
    int lastFence = body.lastIndexOf("```");
    if (lastFence < 0) return body.strip();
    if (!body.substring(lastFence + 3).isBlank()) return t;
    return body.substring(0, lastFence).strip();
  }

  private static boolean isBareLabel(String line) {
    return line.matches("(?i)^#{1,6}\\s*" + LANGUAGE + TRANSLATION_WORD + "\\s*[.:]?$")
        || line.matches("(?i)^\\*{0,2}" + LANGUAGE + TRANSLATION_WORD + "\\*{0,2}\\s*[.:]$");
  }

  private static boolean opensWithLabel(String sourceText) {
    if (sourceText == null) return false;
    String stripped = sourceText.strip();
    int newline = stripped.indexOf('\n');
    String first = (newline < 0 ? stripped : stripped.substring(0, newline)).strip();
    return first.matches("(?i)^[#*\\s]*" + LANGUAGE + TRANSLATION_WORD + "[*\\s]*[.:]?$");
  }

  /**
   * Removes a preamble the model wrote about the translation.
   *
   * <p>Told to return only the translation, the model still opened 4,043 pages with a "###
   * Translation" heading and 1,363 by announcing itself — "Here is the translation preserving all
   * original formatting, proper names, place names and dates:". Neither is on the document, and
   * once headings render large and ruled each of those pages opens with a title the Reichsprotektor
   * never wrote.
   *
   * <p>A spoken preamble must end in a colon, which is what separates the model introducing a
   * translation from a page whose first sentence is about one. Up to two are removed: some
   * responses repeated the heading.
   */
  public static String stripPreamble(String text) {
    return stripPreamble(text, null);
  }

  /**
   * @param sourceText the text that was translated; when it opens with a bare label such as
   *     "Übersetzung" the label is the document's own heading, so the answer's is kept
   */
  public static String stripPreamble(String text, String sourceText) {
    if (text == null) return "";
    String t = text.strip();
    boolean labelIsContent = opensWithLabel(sourceText);
    for (int pass = 0; pass < 2; pass++) {
      int newline = t.indexOf('\n');
      String first = (newline < 0 ? t : t.substring(0, newline)).strip();
      boolean preamble =
          (!labelIsContent && isBareLabel(first))
              || first.matches(
                  "(?i)^(here is|here's|below is)\\s+the\\s+"
                      + LANGUAGE
                      + "(?:translation|translated\\s+(?:text|version))\\b[^\\n]{0,200}:$")
              || first.matches(
                  "(?i)^(zde je|toto je)\\s+(?:(?:český|německý|anglický)\\s+)?"
                      + "(?:překlad|přeložený\\s+text)\\b[^\\n]{0,200}:$")
              || first.matches(
                  "(?i)^(hier ist|nachfolgend)\\s+die\\s+"
                      + "(?:(?:deutsche|tschechische|englische)\\s+)?"
                      + "(?:[üÜ]bersetzung|[üÜ]bersetzte\\s+text)\\b[^\\n]{0,200}:$");
      if (!preamble) break;
      t = newline < 0 ? "" : t.substring(newline + 1).strip();
    }
    return t;
  }
}
