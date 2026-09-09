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
  private final JdbcTemplate jdbc;

  public TranslateBatchStage(String model, JdbcTemplate jdbc) {
    this.model = model;
    this.jdbc = jdbc;
  }

  @Override
  public String jobKind() {
    return "translate_page";
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

  @Override
  public void applyResult(Job job, JsonNode body) {
    String translated =
        body.path("choices").path(0).path("message").path("content").asText("").strip();
    jdbc.update("UPDATE page_text SET text_en = ? WHERE page_id = ?", translated, job.getPageId());
  }
}
