package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.model.Job;

/**
 * Record metadata translation through the provider's batch API.
 *
 * <p>The last translation path still running over HTTP, and the only one with no model history: a
 * title was written straight over record.title_en, so nothing recorded what produced it and a
 * better translation had no way to take over. It ran google/gemma-4-31B, which rendered
 * "soustrastný dopis" — a letter of condolence — as "an obscene letter".
 *
 * <p>Title and description come back as one JSON object rather than two requests, because they are
 * translated together and a description read in the light of its own title is the better
 * translation.
 */
public class RecordTranslateBatchStage implements BatchStage {

  private static final Logger log = LoggerFactory.getLogger(RecordTranslateBatchStage.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * These are catalogue headings, so the reference numbers in them are evidence. The instruction
   * leads with keeping them, and with leaving proper names alone: the previous model translated
   * "ČNST Vlajka", the name of an organisation, into "CNST Flag".
   */
  private static final String INSTRUCTION =
      "Translate the archival catalogue metadata below into English. It describes a record in a"
          + " Czech or German state archive. Preserve proper names, organisation names, place"
          + " names, dates and reference numbers exactly as written — do not translate a name into"
          + " its literal meaning. Do not add commentary.\n\n"
          + "Reply with a JSON object having exactly the keys \"title\" and \"description\"."
          + " Where a field is empty or absent below, return an empty string for it.\n\n";

  private final String model;
  private final JdbcTemplate jdbc;
  private final TranslationService translationService;

  public RecordTranslateBatchStage(
      String model, JdbcTemplate jdbc, TranslationService translationService) {
    this.model = model;
    this.jdbc = jdbc;
    this.translationService = translationService;
  }

  @Override
  public String jobKind() {
    return "translate_record";
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
        jdbc.queryForList(
            "SELECT title, description, metadata_lang FROM record WHERE id = ?", job.getRecordId());
    if (rows.isEmpty()) {
      return null;
    }
    String title = str(rows.get(0).get("title"));
    String description = str(rows.get(0).get("description"));
    String lang = str(rows.get(0).get("metadata_lang"));

    // Already English, or nothing to translate: neither is a failure, and neither is worth a
    // provider call.
    if ("en".equalsIgnoreCase(lang) || (title.isBlank() && description.isBlank())) {
      translationService.recordMetadata(job.getRecordId(), model, title, description);
      return null;
    }

    return Map.of(
        "max_tokens",
        2000,
        "temperature",
        0.1,
        "response_format",
        Map.of("type", "json_object"),
        "messages",
        List.of(
            Map.of(
                "role",
                "user",
                "content",
                INSTRUCTION + "title: " + title + "\ndescription: " + description)));
  }

  @Override
  public void applyResult(Job job, JsonNode body) {
    String content =
        body.path("choices").path(0).path("message").path("content").asText("").strip();
    if (content.isEmpty()) {
      return;
    }
    try {
      JsonNode parsed = MAPPER.readTree(content);
      translationService.recordMetadata(
          job.getRecordId(),
          model,
          parsed.path("title").asText("").strip(),
          parsed.path("description").asText("").strip());
    } catch (Exception e) {
      // A malformed reply loses this record's upgrade, not the batch. The previous translation
      // stays in place because nothing overwrites it.
      log.warn("Unparseable metadata translation for record {}: {}", job.getRecordId(), content, e);
    }
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }
}
