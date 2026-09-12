package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.ai.Translator;
import place.icomb.archiver.model.Job;

/**
 * Translation against a provider with no batch job API: one document, one request.
 *
 * <p>The batch orchestrator speaks Mistral's asynchronous protocol — upload a file of requests,
 * create a job, poll it, download the output. Nothing else implements that, so an OpenAI-compatible
 * endpoint (DeepInfra, vLLM, a local Ollama, OpenAI itself) could be configured but never actually
 * run: the orchestrator would fire {@code POST /v1/files} at it and fail every job.
 *
 * <p>The request itself is identical — {@link Translator} builds the same chat body either way — so
 * only the transport differs, and that is all this class is. Claiming, gating, accounting and
 * completion come from {@link GenericWorker}, exactly as they do for the batch path.
 */
public class SyncTranslateWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(SyncTranslateWorker.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Translator translator;
  private final String apiKey;
  private final JdbcTemplate jdbc;
  private final TranslationService translationService;
  private final ResilientHttpClient http = ResilientHttpClient.builder().build();

  public SyncTranslateWorker(
      String workerId,
      String jobKind,
      Translator translator,
      String apiKey,
      JobService jobService,
      JobEventService jobEventService,
      JdbcTemplate jdbc,
      TranslationService translationService) {
    super(jobService, jobEventService, jobKind, workerId);
    this.translator = translator;
    this.apiKey = apiKey;
    this.jdbc = jdbc;
    this.translationService = translationService;
  }

  @Override
  protected String model() {
    return translator.model();
  }

  @Override
  protected String providerUrl() {
    return translator.endpoint();
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected void processJob(Job job) throws Exception {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT text_raw FROM page_text WHERE page_id = ?", job.getPageId());
    if (rows.isEmpty()) {
      return;
    }
    String text = (String) rows.get(0).get("text_raw");

    // A blank page is legitimately blank. Storing an empty translation is the correct answer;
    // asking a model to translate nothing is not, and failing the job says the page is broken.
    if (text == null || text.isBlank()) {
      jdbc.update("UPDATE page_text SET text_en = '' WHERE page_id = ?", job.getPageId());
      return;
    }

    Map<String, Object> body = translator.request(text, null);
    Map<String, Object> withModel = new java.util.LinkedHashMap<>(body);
    withModel.put("model", translator.model());

    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(translator.endpoint() + translator.endpointPath()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(withModel)))
            .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "translate failed: HTTP "
              + response.statusCode()
              + " "
              + response.body().substring(0, Math.min(300, response.body().length())));
    }

    JsonNode parsed = MAPPER.readTree(response.body());
    String translated = translator.parse(parsed);

    // Every model's output is kept. text_en is only a cache of whichever is preferred, so a cheap
    // translation is never destroyed by a better one.
    translationService.record(job.getPageId(), translator.model(), translated);
  }
}
