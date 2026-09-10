package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.model.Job;

/**
 * Page translation through the provider's batch API.
 *
 * <p>Replaces an interactive path that took ~27 seconds per page against a provider that queued
 * concurrent requests rather than parallelising them, making 128,484 pages a two-day job. The same
 * model answers in under two seconds uncontended, and batching removes the per-request overhead
 * entirely.
 */
public class TranslateBatchStage implements BatchStage {

  /**
   * Names, dates and reference numbers are evidence in these documents, so the instruction leads
   * with preserving them. A model that renders "Führungszeugnis" as "Leadership Certificate" —
   * which one candidate did — is worse than no translation, because it reads as authoritative.
   */
  private static final String INSTRUCTION =
      "Translate the following German or Czech archival document text into English. "
          + "Preserve all proper names, place names, dates, reference numbers and titles exactly "
          + "as written. Keep the markdown structure. Do not add commentary or explanation. "
          + "Output only the translation.\n\n";

  private final String model;
  private final String jobKind;
  private final JdbcTemplate jdbc;
  private final TranslationService translationService;

  public TranslateBatchStage(
      String model, String jobKind, JdbcTemplate jdbc, TranslationService translationService) {
    this.model = model;
    this.jobKind = jobKind;
    this.jdbc = jdbc;
    this.translationService = translationService;
  }

  @Override
  public String jobKind() {
    return jobKind;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public String endpoint() {
    return "/v1/chat/completions";
  }

  @Override
  public Map<String, Object> buildRequestBody(Job job) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT text_raw FROM page_text WHERE page_id = ?", job.getPageId());
    if (rows.isEmpty()) return null;
    String text = (String) rows.get(0).get("text_raw");
    // A blank page is legitimately blank; store an empty translation rather than asking a model
    // to translate nothing.
    if (text == null || text.isBlank()) {
      jdbc.update("UPDATE page_text SET text_en = '' WHERE page_id = ?", job.getPageId());
      return null;
    }
    return Map.of(
        "max_tokens",
        4000,
        "temperature",
        0.1,
        "messages",
        List.of(Map.of("role", "user", "content", INSTRUCTION + text)));
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
  static String unwrapCodeFence(String text) {
    if (text == null) return "";
    String t = text.strip();
    if (!t.startsWith("```")) return t;

    int firstNewline = t.indexOf('\n');
    if (firstNewline < 0) return t;
    // The opening fence may carry a language tag, e.g. ```markdown
    String opener = t.substring(3, firstNewline).strip();
    if (!opener.isEmpty() && !opener.matches("[A-Za-z0-9_+-]+")) return t;

    String body = t.substring(firstNewline + 1);
    int lastFence = body.lastIndexOf("```");
    if (lastFence < 0) return body.strip();
    // Anything after the closing fence would be content outside it; leave such text alone.
    if (!body.substring(lastFence + 3).isBlank()) return t;
    return body.substring(0, lastFence).strip();
  }

  /**
   * Removes a preamble the model wrote about the translation.
   *
   * <p>Told to return only the translation, the model still opened 4,043 pages with a "###
   * Translation" heading and 1,363 by announcing itself — "Here is the translation preserving all
   * original formatting, proper names, place names and dates:". Neither is on the document. A stray
   * sentence was survivable while everything rendered as flat prose; once headings are set large
   * and ruled, each of those pages opens with a title the Reichsprotektor never wrote.
   *
   * <p>A spoken preamble must end in a colon, which is what separates the model introducing a
   * translation from a page whose first sentence is about one. Up to two are removed: some
   * responses repeated the heading.
   */
  static String stripPreamble(String text) {
    if (text == null) return "";
    String t = text.strip();
    for (int pass = 0; pass < 2; pass++) {
      int newline = t.indexOf('\n');
      String first = (newline < 0 ? t : t.substring(0, newline)).strip();
      boolean preamble =
          first.matches("(?i)^#{1,6}\\s*(english\\s+)?translations?\\s*[.:]?$")
              || first.matches("(?i)^\\*{0,2}(english\\s+)?translations?\\*{0,2}\\s*[.:]$")
              || first.matches(
                  "(?i)^(here is|here's|below is)\\s+the\\s+(english\\s+)?translation\\b[^\\n]{0,200}:$");
      if (!preamble) break;
      t = newline < 0 ? "" : t.substring(newline + 1).strip();
    }
    return t;
  }

  @Override
  public void applyResult(Job job, JsonNode body) {
    String translated =
        stripPreamble(
            unwrapCodeFence(
                body.path("choices").path(0).path("message").path("content").asText("").strip()));

    // Every model's output is kept. text_en is only a cache of whichever is preferred, so a
    // cheap translation is never destroyed by a better one — and an upgrade already done can
    // be recognised and refused rather than paid for twice.
    translationService.record(job.getPageId(), model, translated);
  }
}
