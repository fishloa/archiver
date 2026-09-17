package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.ai.TranskribusConfig;

/**
 * The Transkribus Metagrapho API: submit one page, poll, take the transcription.
 *
 * <p>Verified against the live OpenAPI document (Metagrapho API 1.13.1) rather than written from
 * documentation: {@code POST /processes} takes {@code {config:{textRecognition:{htrId,
 * languageModel}}, image:{base64}}} and answers with a {@code processId} and a status of CREATED,
 * WAITING, RUNNING, FINISHED or FAILED; {@code GET /processes/{id}} carries the same status and,
 * once finished, {@code content.text}; {@code GET /processes/{id}/page} carries PAGE XML.
 *
 * <p>Access tokens come from READCOOP's Keycloak by password grant and are cached until shortly
 * before they expire, because a token request per page would be a second round trip for every
 * credit spent.
 */
public class TranskribusClient {

  private static final Logger log = LoggerFactory.getLogger(TranskribusClient.class);

  /** Refresh this long before the token actually expires, to survive a slow submission. */
  private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

  /** The API's own ceiling on base64 image length; a larger page must be downscaled first. */
  public static final int MAX_BASE64_LENGTH = 27_962_027;

  private final TranskribusConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper mapper = new ObjectMapper();

  private String accessToken;
  private Instant tokenExpiry = Instant.EPOCH;

  public TranskribusClient(TranskribusConfig config) {
    this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
  }

  /** Test seam: lets a test supply a client pointed at a stub server. */
  public TranskribusClient(TranskribusConfig config, HttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
  }

  /** One page's result. */
  public record Result(String text, int htrId, long processId, JsonNode raw) {}

  /**
   * Transcribes one page image and blocks until Transkribus finishes it.
   *
   * @param imageBytes the page image
   * @param htrId the model to apply
   * @throws TranskribusException if the page fails, or takes longer than {@code maxPollMs}
   */
  public Result transcribe(byte[] imageBytes, int htrId) throws Exception {
    String base64 = Base64.getEncoder().encodeToString(imageBytes);
    if (base64.length() > MAX_BASE64_LENGTH) {
      throw new TranskribusException(
          "Image is %d base64 characters, over the API's %d limit"
              .formatted(base64.length(), MAX_BASE64_LENGTH));
    }

    long processId = submit(base64, htrId);
    JsonNode finished = awaitCompletion(processId);
    String text = finished.path("content").path("text").asText("");
    return new Result(text, htrId, processId, finished);
  }

  private long submit(String base64, int htrId) throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "config",
                Map.of(
                    "textRecognition",
                    Map.of("htrId", htrId, "languageModel", config.languageModel())),
                "image",
                Map.of("base64", base64)));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + config.processesPath()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMinutes(5)));

    if (response.statusCode() == 429) {
      throw new TranskribusException("Rate limited or out of credits (HTTP 429)");
    }
    if (response.statusCode() != 200) {
      throw new TranskribusException(
          "Submit returned HTTP " + response.statusCode() + ": " + brief(response.body()));
    }

    JsonNode json = mapper.readTree(response.body());
    long processId = json.path("processId").asLong();
    if (processId == 0) {
      throw new TranskribusException("Submit returned no processId: " + brief(response.body()));
    }
    log.info("Transkribus process {} submitted (htrId={})", processId, htrId);
    return processId;
  }

  private JsonNode awaitCompletion(long processId) throws Exception {
    Instant deadline = Instant.now().plusMillis(config.maxPollMs());
    while (true) {
      JsonNode status = status(processId);
      String state = status.path("status").asText("");
      switch (state) {
        case "FINISHED" -> {
          return status;
        }
        case "FAILED" ->
            throw new TranskribusException(
                "Process " + processId + " failed: " + brief(status.toString()));
        case "CREATED", "WAITING", "RUNNING" -> {
          if (Instant.now().isAfter(deadline)) {
            throw new TranskribusException(
                "Process %d still %s after %dms".formatted(processId, state, config.maxPollMs()));
          }
          Thread.sleep(config.pollIntervalMs());
        }
        default ->
            throw new TranskribusException(
                "Process " + processId + " reported unknown status " + state);
      }
    }
  }

  /** The process's current state, including {@code content.text} once it has finished. */
  public JsonNode status(long processId) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/processes/" + processId))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofMinutes(2)));
    if (response.statusCode() != 200) {
      throw new TranskribusException(
          "Status returned HTTP " + response.statusCode() + ": " + brief(response.body()));
    }
    return mapper.readTree(response.body());
  }

  /** PAGE XML for a finished process — line coordinates as well as text. */
  public String pageXml(long processId) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/processes/" + processId + "/page"))
                .header("Accept", "application/xml")
                .GET()
                .timeout(Duration.ofMinutes(2)));
    if (response.statusCode() != 200) {
      throw new TranskribusException("PAGE XML returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  /**
   * Transkribus's public model catalogue.
   *
   * <p>Needs no credential, so the choice of model can be reviewed without one. Returns the raw
   * {@code trpModelMetadata} array: modelId, name, isoLanguages, docType, finalCer.
   */
  public static JsonNode publicModels(HttpClient httpClient) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://transkribus.eu/TrpServer/rest/models/text"))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new TranskribusException("Model catalogue returned HTTP " + response.statusCode());
    }
    return new ObjectMapper().readTree(response.body()).path("trpModelMetadata");
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    builder.header("Authorization", "Bearer " + accessToken());
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A cached access token, refreshed shortly before it expires. */
  synchronized String accessToken() throws Exception {
    if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
      return accessToken;
    }

    String form =
        "grant_type=password&client_id=%s&username=%s&password=%s"
            .formatted(enc(config.clientId()), enc(config.username()), enc(config.password()));

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofMinutes(1))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      // Never log the body of a failed token request: it echoes the form back on some errors.
      throw new TranskribusException(
          "Token request returned HTTP " + response.statusCode() + " for " + config.tokenUrl());
    }

    JsonNode json = mapper.readTree(response.body());
    accessToken = json.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      throw new TranskribusException("Token response carried no access_token");
    }
    long expiresIn = json.path("expires_in").asLong(300);
    tokenExpiry = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
    return accessToken;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String brief(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 400 ? body : body.substring(0, 400) + "…";
  }

  /** A Transkribus call that cannot be retried usefully without changing something. */
  public static class TranskribusException extends RuntimeException {
    public TranskribusException(String message) {
      super(message);
    }
  }

  /** Line-level text from PAGE XML, for callers that want lines rather than one blob. */
  public static List<String> textLines(JsonNode statusBody) {
    var lines = new java.util.ArrayList<String>();
    for (JsonNode region : statusBody.path("content").path("regions")) {
      for (JsonNode line : region.path("lines")) {
        String text = line.path("text").asText("");
        if (!text.isBlank()) {
          lines.add(text);
        }
      }
    }
    return lines;
  }
}
