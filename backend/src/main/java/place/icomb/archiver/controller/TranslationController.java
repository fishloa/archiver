package place.icomb.archiver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.http.HttpClient;
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

  private final String workerUrl;
  private final String anthropicApiKey;
  private final HttpClient httpClient = HttpClient.newHttpClient();
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

  public TranslationController(
      @Value("${archiver.translate-worker.url:http://translate-worker:8001}") String workerUrl,
      @Value("${archiver.anthropic.api-key:}") String anthropicApiKey) {
    this.workerUrl = workerUrl;
    this.anthropicApiKey = anthropicApiKey;
  }

  public record TranslateRequest(String text, String sourceLang, String targetLang) {}

  public record TranslateResponse(String translatedText, String sourceLang, String targetLang) {}

  public record LangPair(String source, String target) {}

  public record Capabilities(java.util.List<LangPair> pairs) {}

  @GetMapping("/capabilities")
  @Operation(summary = "Get supported language pairs")
  public ResponseEntity<?> capabilities() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(workerUrl + "/capabilities")).GET().build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        return ResponseEntity.status(response.statusCode()).body(response.body());
      }

      return ResponseEntity.ok().header("Content-Type", "application/json").body(response.body());
    } catch (Exception e) {
      log.error("Failed to fetch translation capabilities", e);
      return ResponseEntity.status(503).body("{\"error\":\"Translation service unavailable\"}");
    }
  }

  @PostMapping
  @Operation(summary = "Translate text between supported language pairs")
  public ResponseEntity<?> translate(@RequestBody TranslateRequest request) {
    if (request.text() == null || request.text().isBlank()) {
      return ResponseEntity.badRequest().body("{\"error\":\"Text is required\"}");
    }

    try {
      String workerBody =
          objectMapper.writeValueAsString(
              new java.util.LinkedHashMap<>() {
                {
                  put("text", request.text());
                  put("source_lang", request.sourceLang());
                  put("target_lang", request.targetLang() != null ? request.targetLang() : "en");
                }
              });

      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(workerUrl + "/translate"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(workerBody))
              .build();

      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Translation worker returned {}: {}", response.statusCode(), response.body());
        return ResponseEntity.status(response.statusCode()).body(response.body());
      }

      var tree = objectMapper.readTree(response.body());
      var result =
          new TranslateResponse(
              tree.get("translated_text").asText(),
              tree.get("source_lang").asText(),
              tree.get("target_lang").asText());

      return ResponseEntity.ok(result);
    } catch (Exception e) {
      log.error("Translation failed", e);
      return ResponseEntity.status(503)
          .body("{\"error\":\"Translation service unavailable: " + e.getMessage() + "\"}");
    }
  }

  @PostMapping("/claude")
  @Operation(summary = "Translate text via Claude API (requires login)")
  public ResponseEntity<?> translateWithClaude(@RequestBody TranslateRequest request) {
    if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
      return ResponseEntity.status(503).body("{\"error\":\"Claude translation not configured\"}");
    }
    if (request.text() == null || request.text().isBlank()) {
      return ResponseEntity.badRequest().body("{\"error\":\"Text is required\"}");
    }

    String srcName = LANG_NAMES.getOrDefault(request.sourceLang(), request.sourceLang());
    String tgtName =
        LANG_NAMES.getOrDefault(
            request.targetLang() != null ? request.targetLang() : "en",
            request.targetLang() != null ? request.targetLang() : "English");

    String prompt =
        "Translate the following text from "
            + srcName
            + " to "
            + tgtName
            + ". This is a historical/archival document, so handle archaic language, old spelling"
            + " conventions, and period-specific terminology faithfully. Preserve the original"
            + " formatting (line breaks, paragraphs, lists). Return ONLY the translation with no"
            + " commentary, preamble, or explanation.\n\n"
            + request.text();

    try {
      String body =
          objectMapper.writeValueAsString(
              Map.of(
                  "model",
                  "claude-sonnet-4-20250514",
                  "max_tokens",
                  4096,
                  "messages",
                  java.util.List.of(Map.of("role", "user", "content", prompt))));

      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.anthropic.com/v1/messages"))
              .header("Content-Type", "application/json")
              .header("x-api-key", anthropicApiKey)
              .header("anthropic-version", "2023-06-01")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.error("Claude API error {}: {}", response.statusCode(), response.body());
        return ResponseEntity.status(502).body("{\"error\":\"Translation failed\"}");
      }

      var tree = objectMapper.readTree(response.body());
      String translatedText = tree.at("/content/0/text").asText("");
      String targetLang = request.targetLang() != null ? request.targetLang() : "en";

      return ResponseEntity.ok(
          new TranslateResponse(translatedText, request.sourceLang(), targetLang));
    } catch (Exception e) {
      log.error("Claude translation failed", e);
      return ResponseEntity.status(502)
          .body("{\"error\":\"Translation failed: " + e.getMessage() + "\"}");
    }
  }
}
