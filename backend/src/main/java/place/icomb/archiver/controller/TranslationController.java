package place.icomb.archiver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/translate")
@Tag(name = "Translation", description = "On-demand GPU translation")
public class TranslationController {

  private static final Logger log = LoggerFactory.getLogger(TranslationController.class);

  private final String mistralBaseUrl;
  private final String mistralApiKey;
  private final place.icomb.archiver.service.ResilientHttpClient httpClient =
      place.icomb.archiver.service.ResilientHttpClient.builder().build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final Map<String, String> LANG_NAMES =
      Map.of(
          "de",
          "German",
          "cs",
          "Czech",
          "en",
          "English",
          "fr",
          "French",
          "pl",
          "Polish",
          "hu",
          "Hungarian");

  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public TranslationController(
      @Value("${archiver.ocr.mistral.base-url:https://api.mistral.ai}") String mistralBaseUrl,
      @Value("${archiver.ocr.mistral.api-key:}") String mistralApiKey,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
    this.mistralBaseUrl = mistralBaseUrl;
    this.mistralApiKey = mistralApiKey;
    this.jdbcTemplate = jdbcTemplate;
  }

  public record TranslateRequest(String text, String sourceLang, String targetLang) {}

  public record TranslateResponse(String translatedText, String sourceLang, String targetLang) {}

  /**
   * Languages the translation UI offers.
   *
   * <p>Read from the archive's own content rather than a list held in code: the LLM translates any
   * pair on demand, so the only question worth answering is which languages are actually present,
   * and the records already know. A hand-maintained list would drift the moment a scraper brought
   * in something new — and the previous one, MarianMT's downloaded language pairs, became empty
   * when that engine was removed and silently emptied both dropdowns.
   *
   * <p>Codes only. The UI names them through the browser's own locale data, so the names are
   * localised to the reader without a translation table living here.
   */
  @GetMapping("/capabilities")
  @Operation(summary = "Languages present in the archive, offered for translation")
  public ResponseEntity<Map<String, Object>> capabilities() {
    java.util.List<String> languages =
        jdbcTemplate.queryForList(
            """
            SELECT DISTINCT lang FROM (
                SELECT lang FROM record WHERE lang IS NOT NULL
                UNION
                SELECT metadata_lang FROM record WHERE metadata_lang IS NOT NULL
            ) l
            ORDER BY lang
            """,
            String.class);
    return ResponseEntity.ok(Map.of("languages", languages));
  }

  /**
   * On-demand translation, straight to the provider.
   *
   * <p>This used to post to the Python translate-worker, which ran google/gemma-4-31B. That worker
   * also claimed translate_page jobs alongside the batch pipeline, so the two raced for the same
   * work: on a 228-page dissertation the batch took 226 pages and gemma took 2. It has been
   * retired, and this endpoint now uses the same model the pipeline prefers.
   */
  @PostMapping
  @Operation(summary = "Translate text between supported language pairs")
  public ResponseEntity<?> translate(@RequestBody TranslateRequest request) {
    if (request.text() == null || request.text().isBlank()) {
      return ResponseEntity.badRequest().body("{\"error\":\"Text is required\"}");
    }
    if (mistralApiKey == null || mistralApiKey.isBlank()) {
      return ResponseEntity.status(503).body("{\"error\":\"Translation not configured\"}");
    }

    String targetLang = request.targetLang() != null ? request.targetLang() : "en";
    String srcName = LANG_NAMES.getOrDefault(request.sourceLang(), request.sourceLang());
    String tgtName = LANG_NAMES.getOrDefault(targetLang, targetLang);

    String prompt =
        "Translate the following text from "
            + (srcName == null || srcName.isBlank() ? "its original language" : srcName)
            + " to "
            + tgtName
            + ". This is an archival document, so preserve proper names, place names, dates and"
            + " reference numbers exactly as written, and keep the original formatting. Return"
            + " only the translation.\n\n"
            + request.text();

    try {
      String body =
          objectMapper.writeValueAsString(
              Map.of(
                  "model",
                  place.icomb.archiver.service.TranslationModels.UPGRADE_MODEL,
                  "temperature",
                  0.1,
                  "max_tokens",
                  4000,
                  "messages",
                  java.util.List.of(Map.of("role", "user", "content", prompt))));

      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(mistralBaseUrl + "/v1/chat/completions"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + mistralApiKey)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Translation provider returned {}: {}", response.statusCode(), response.body());
        return ResponseEntity.status(502).body("{\"error\":\"Translation failed\"}");
      }

      var tree = objectMapper.readTree(response.body());
      String translated =
          tree.path("choices").path(0).path("message").path("content").asText("").strip();
      return ResponseEntity.ok(new TranslateResponse(translated, request.sourceLang(), targetLang));
    } catch (Exception e) {
      log.error("Translation failed", e);
      return ResponseEntity.status(503).body("{\"error\":\"Translation service unavailable\"}");
    }
  }
}
