package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.ai.TranskribusConfig;

/**
 * Transkribus through its classic TrpServer API.
 *
 * <p>The newer Metagrapho API takes an image and gives back text, which is what this wants, but it
 * is gated: on a free account a valid token carries only the "Transkribus User" role and every call
 * to it answers HTTP 401. The classic API is open to the same account, so this is the route that
 * works — at the cost of a four-step dance, because it is built around collections of documents
 * rather than single pages:
 *
 * <ol>
 *   <li>{@code POST /uploads?collId=} with a descriptor naming the page — returns an uploadId,
 *       which is also the document id
 *   <li>{@code PUT /uploads/{id}} with the image as multipart field {@code img}
 *   <li>{@code POST /pylaia/{coll}/{model}/recognition} or {@code POST
 *       /recognition/{coll}/{model}/trhtr} — returns a job id as plain text
 *   <li>poll {@code GET /jobs/{id}}, then read the transcript URL out of {@code GET
 *       /collections/{coll}/{doc}/fulldoc}
 * </ol>
 *
 * <p>Which of the two recognition endpoints to call depends on the model's own engine, which the
 * public model catalogue reports as {@code provider}: PyLaia for the community models, TrHtr for
 * the super models. Calling the wrong one fails, so the provider is looked up rather than assumed.
 * TrHtr additionally needs a paid plan — a free account is refused with "You are not allowed for
 * TrHtr Recognition!".
 */
public class TranskribusTrpClient implements HtrEngine {

  private static final Logger log = LoggerFactory.getLogger(TranskribusTrpClient.class);

  private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);
  private static final Pattern TEXT_LINE =
      Pattern.compile("<TextLine[^>]*>.*?<Unicode>(.*?)</Unicode>", Pattern.DOTALL);

  private final TranskribusConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<Integer, String> providerByModel = new HashMap<>();

  private String accessToken;
  private Instant tokenExpiry = Instant.EPOCH;
  private Integer collectionId;

  public TranskribusTrpClient(TranskribusConfig config) {
    this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
  }

  public TranskribusTrpClient(TranskribusConfig config, HttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
  }

  @Override
  public Result transcribe(byte[] imageBytes, int modelId) throws Exception {
    int collId = collectionId();
    String fileName = "page-" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";

    long docId = createDocument(collId, fileName);
    uploadImage(docId, fileName, imageBytes);
    String jobId = startRecognition(collId, docId, modelId);
    awaitJob(jobId);

    String pageXml = fetchPageXml(collId, docId, modelId);
    String text = textFromPageXml(pageXml);

    if (text.isBlank()) {
      throw new TranskribusClient.TranskribusException(
          "Job %s finished but its PAGE XML held no text".formatted(jobId));
    }
    return new Result(text, modelId, jobId, pageXml);
  }

  /** The collection new documents go into: the configured one, or the account's first. */
  public synchronized int collectionId() throws Exception {
    if (collectionId != null) {
      return collectionId;
    }
    int configured = config.collectionId();
    if (configured > 0) {
      collectionId = configured;
      return collectionId;
    }
    JsonNode collections = mapper.readTree(get("/collections/list", "application/json"));
    if (!collections.isArray() || collections.isEmpty()) {
      throw new TranskribusClient.TranskribusException(
          "The Transkribus account has no collection to upload into");
    }
    collectionId = collections.get(0).path("colId").asInt();
    log.info(
        "Transkribus: using collection {} ({})",
        collectionId,
        collections.get(0).path("colName").asText());
    return collectionId;
  }

  /**
   * Creates a one-page document and returns its id.
   *
   * <p>The upload id doubles as the document id, which is not obvious: the descriptor comes back
   * with {@code md.docId} still -1 even after the image is in, and the real id only shows up in the
   * collection listing — where it equals the upload id.
   */
  long createDocument(int collId, String fileName) throws Exception {
    var page = mapper.createObjectNode();
    page.put("fileName", fileName);
    page.put("pageNr", 1);
    var descriptor = mapper.createObjectNode();
    var md = descriptor.putObject("md");
    md.put("title", config.documentTitle());
    md.put("author", "archiver");
    md.put("genre", "");
    md.put("writer", "");
    descriptor.putObject("pageList").putArray("pages").add(page);

    String body =
        post(
            "/uploads?collId=" + collId,
            HttpRequest.BodyPublishers.ofString(descriptor.toString()),
            "application/json");
    long uploadId = mapper.readTree(body).path("uploadId").asLong();
    if (uploadId == 0) {
      throw new TranskribusClient.TranskribusException("Upload returned no uploadId: " + body);
    }
    return uploadId;
  }

  void uploadImage(long uploadId, String fileName, byte[] imageBytes) throws Exception {
    String boundary = UUID.randomUUID().toString().replace("-", "");
    var body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"img\"; filename=\""
                + fileName
                + "\"\r\nContent-Type: image/jpeg\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(imageBytes);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/uploads/" + uploadId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .timeout(Duration.ofMinutes(10)));
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "Image upload returned HTTP " + response.statusCode());
    }
  }

  /** Starts recognition and returns the job id, which the API answers as a bare number. */
  String startRecognition(int collId, long docId, int modelId) throws Exception {
    String provider = providerFor(modelId);
    String path =
        "TrHtr".equalsIgnoreCase(provider)
            ? "/recognition/%d/%d/trhtr?id=%d&pages=1".formatted(collId, modelId, docId)
            : "/pylaia/%d/%d/recognition?id=%d&pages=1".formatted(collId, modelId, docId);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMinutes(5)));

    if (response.statusCode() == 403) {
      throw new TranskribusClient.TranskribusException(
          "Transkribus refused model %d (%s): %s — the super models need a paid plan"
              .formatted(modelId, provider, response.body().strip()));
    }
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "Recognition returned HTTP " + response.statusCode() + ": " + response.body().strip());
    }
    return response.body().strip();
  }

  private void awaitJob(String jobId) throws Exception {
    Instant deadline = Instant.now().plusMillis(config.maxPollMs());
    while (true) {
      JsonNode job = mapper.readTree(get("/jobs/" + jobId, "application/json"));
      String state = job.path("state").asText("");
      switch (state) {
        case "FINISHED" -> {
          return;
        }
        case "FAILED", "CANCELED", "UNSUCCESSFUL" ->
            throw new TranskribusClient.TranskribusException(
                "Job %s ended %s: %s".formatted(jobId, state, job.path("description").asText("")));
        default -> {
          if (Instant.now().isAfter(deadline)) {
            throw new TranskribusClient.TranskribusException(
                "Job %s still %s after %dms (%s)"
                    .formatted(
                        jobId, state, config.maxPollMs(), job.path("description").asText("")));
          }
          // A free-tier job queues behind everyone else's: "51 in Queue. Your job priority: low
          // (using free credits)". Minutes, not seconds.
          Thread.sleep(config.pollIntervalMs());
        }
      }
    }
  }

  /** The PAGE XML the recognition produced, found through the document's transcript list. */
  String fetchPageXml(int collId, long docId, int modelId) throws Exception {
    JsonNode fullDoc =
        mapper.readTree(
            get("/collections/%d/%d/fulldoc".formatted(collId, docId), "application/json"));

    String url = null;
    for (JsonNode page : fullDoc.path("pageList").path("pages")) {
      for (JsonNode transcript : page.path("tsList").path("transcripts")) {
        // Transcripts are listed newest first and the tool name names the model, so the right one
        // is identifiable even when a page has been read several times.
        if (transcript.path("toolName").asText("").contains(String.valueOf(modelId))) {
          url = transcript.path("url").asText(null);
          break;
        }
      }
      if (url != null) {
        break;
      }
    }
    if (url == null) {
      throw new TranskribusClient.TranskribusException(
          "No transcript for model %d on document %d".formatted(modelId, docId));
    }

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "Transcript fetch returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  /**
   * A transcript sitting in a Transkribus document, ready to be imported.
   *
   * @param pageNr the page within the Transkribus document
   * @param imageFileName the name the image was uploaded under — {@code rec3505_seq0110.jpg} —
   *     which is how a transcript identifies the archive page it belongs to without a mapping file
   * @param toolName the provider's own description, e.g. "TrHtr recognition 2.51.0 - Model: 579509,
   *     Text Titan II"
   */
  public record TranscriptRef(
      int pageNr, String imageFileName, String url, String toolName, long tsId) {}

  /**
   * Every page's newest transcript in a document.
   *
   * <p>For importing work done in the web app, which is the only way to run the super models: the
   * API may not start them, but it may read what they produced. Transkribus lists transcripts
   * newest first, so the first one carrying a URL is the current transcription.
   */
  public List<TranscriptRef> listTranscripts(int collId, long docId) throws Exception {
    JsonNode fullDoc =
        mapper.readTree(
            get("/collections/%d/%d/fulldoc".formatted(collId, docId), "application/json"));
    var out = new java.util.ArrayList<TranscriptRef>();
    for (JsonNode page : fullDoc.path("pageList").path("pages")) {
      for (JsonNode transcript : page.path("tsList").path("transcripts")) {
        String url = transcript.path("url").asText(null);
        String tool = transcript.path("toolName").asText("");
        // A page always carries an original "NEW" transcript with no tool: that is the empty
        // layout, not a transcription, and importing it would blank the page.
        if (url == null || tool.isBlank()) {
          continue;
        }
        out.add(
            new TranscriptRef(
                page.path("pageNr").asInt(),
                page.path("imgFileName").asText(""),
                url,
                tool,
                transcript.path("tsId").asLong()));
        break;
      }
    }
    return out;
  }

  /** Documents in a collection, newest first: {@code (docId, title, pages)}. */
  public JsonNode listDocuments(int collId) throws Exception {
    return mapper.readTree(get("/collections/" + collId + "/list", "application/json"));
  }

  /** Fetches one transcript's PAGE XML by its URL. */
  public String fetchTranscript(String url) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/xml")
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "Transcript fetch returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  /**
   * The model that produced a PAGE XML, read out of its own Creator string.
   *
   * <p>{@code prov=READ-COOP:name=TrHtr:version=2.51.0:model_id=579509:date=17_09_2026}. Taken from
   * the artefact rather than from what we think was run, so a transcription carries the provenance
   * of the model that actually made it.
   */
  public static int modelIdFromPageXml(String pageXml) {
    Matcher m = Pattern.compile("model_id=(\\d+)").matcher(pageXml);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  /** Line text in reading order, one line per {@code TextLine}. */
  static String textFromPageXml(String pageXml) {
    var out = new StringBuilder();
    Matcher m = TEXT_LINE.matcher(pageXml);
    while (m.find()) {
      String line = m.group(1).trim();
      if (!line.isEmpty()) {
        out.append(unescape(line)).append('\n');
      }
    }
    return out.toString().trim();
  }

  private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");

  /**
   * XML entities back to their characters.
   *
   * <p>Numeric entities as well as named ones. Transkribus writes literal UTF-8 in practice, but a
   * single {@code &#228;} left unresolved would put "verl&#228;ngert" into a transcription that is
   * meant to be quotable, and the point of this archive is that the stored text is what the engine
   * actually said.
   *
   * <p>{@code &amp;} is resolved last, so an escaped ampersand cannot be re-read as the start of
   * another entity.
   */
  static String unescape(String s) {
    Matcher m = NUMERIC_ENTITY.matcher(s);
    var sb = new StringBuilder();
    while (m.find()) {
      int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
      m.appendReplacement(sb, Matcher.quoteReplacement(Character.toString(code)));
    }
    m.appendTail(sb);

    return sb.toString()
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }

  /**
   * Which engine a model runs on, from Transkribus's public catalogue.
   *
   * <p>Needs no credential and is cached for the life of the client: it decides which recognition
   * endpoint to call, and calling the wrong one wastes the page.
   */
  String providerFor(int modelId) {
    return providerByModel.computeIfAbsent(
        modelId,
        id -> {
          try {
            for (JsonNode model : TranskribusClient.publicModels(httpClient)) {
              if (model.path("modelId").asInt() == id) {
                return model.path("provider").asText("PyLaia");
              }
            }
          } catch (Exception e) {
            log.warn("Could not read the Transkribus model catalogue: {}", e.getMessage());
          }
          // PyLaia is the safer default: it is what a free account is allowed to run.
          return "PyLaia";
        });
  }

  private String get(String path, String accept) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .header("Accept", accept)
                .GET()
                .timeout(Duration.ofMinutes(2)));
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "GET " + path + " returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  private String post(String path, HttpRequest.BodyPublisher body, String contentType)
      throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .POST(body)
                .timeout(Duration.ofMinutes(5)));
    if (response.statusCode() != 200) {
      throw new TranskribusClient.TranskribusException(
          "POST " + path + " returned HTTP " + response.statusCode() + ": " + response.body());
    }
    return response.body();
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    builder.header("Authorization", "Bearer " + accessToken());
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * A cached access token.
   *
   * <p>Transkribus tokens last 300 seconds, which is shorter than a queued job, so this refreshes
   * rather than being fetched once per page: a poll loop with a stale token reads as a mysterious
   * HTML error page rather than a 401.
   */
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
      // Not the body: a failed token request can echo the form back.
      throw new TranskribusClient.TranskribusException(
          "Token request returned HTTP " + response.statusCode());
    }
    JsonNode json = mapper.readTree(response.body());
    accessToken = json.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      throw new TranskribusClient.TranskribusException("Token response carried no access_token");
    }
    tokenExpiry =
        Instant.now().plusSeconds(json.path("expires_in").asLong(300)).minus(EXPIRY_MARGIN);
    return accessToken;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
