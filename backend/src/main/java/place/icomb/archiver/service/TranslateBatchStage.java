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

  private final place.icomb.archiver.ai.Translator translator;
  private final String model;
  private final String jobKind;
  private final JdbcTemplate jdbc;
  private final TranslationService translationService;

  public TranslateBatchStage(
      place.icomb.archiver.ai.Translator translator,
      String jobKind,
      JdbcTemplate jdbc,
      TranslationService translationService) {
    this.translator = translator;
    this.model = translator.model();
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
    return translator.endpointPath();
  }

  @Override
  public boolean resolveLocally(Job job) {
    // A blank page is legitimately blank. Storing an empty translation is the correct answer;
    // asking a model to translate nothing is not, and failing the job says the page is broken.
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT text_raw FROM page_text WHERE page_id = ?", job.getPageId());
    if (rows.isEmpty()) {
      return false;
    }
    String text = (String) rows.get(0).get("text_raw");
    if (text == null || text.isBlank()) {
      jdbc.update("UPDATE page_text SET text_en = '' WHERE page_id = ?", job.getPageId());
      return true;
    }
    return false;
  }

  @Override
  public Map<String, Object> buildRequestBody(Job job) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT text_raw FROM page_text WHERE page_id = ?", job.getPageId());
    if (rows.isEmpty()) return null;
    String text = (String) rows.get(0).get("text_raw");
    return translator.request(text, null);
  }

  @Override
  public void applyResult(Job job, JsonNode body) {
    String translated = translator.parse(body);

    // Every model's output is kept. text_en is only a cache of whichever is preferred, so a
    // cheap translation is never destroyed by a better one — and an upgrade already done can
    // be recognised and refused rather than paid for twice.
    translationService.record(job.getPageId(), model, translated);
  }
}
