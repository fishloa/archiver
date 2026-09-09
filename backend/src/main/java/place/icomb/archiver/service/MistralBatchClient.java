package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mistral's batch API, as HTTP and nothing else.
 *
 * <p>Extracted so any pipeline stage can be batched without reimplementing upload, polling and
 * orphan lookup — and, more to the point, without reimplementing them slightly differently. The
 * guards around double billing and page loss live in {@link BatchOrchestrator}; this class knows
 * only how to talk to the provider.
 */
public class MistralBatchClient {

  private static final Logger log = LoggerFactory.getLogger(MistralBatchClient.class);

  private final java.net.http.HttpClient http =
      java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private final ObjectMapper mapper = new ObjectMapper();
  private final String apiKey;
  private final String baseUrl;

  public MistralBatchClient(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
  }

  /**
   * Uploads a batch request file.
   *
   * <p>Hand-rolled multipart streamed from disk: the JDK HTTP client has no multipart support, and
   * a full batch is hundreds of megabytes, which must never be assembled in heap.
   */
  public String uploadInput(Path jsonl) throws Exception {
    String boundary = "----archiver" + System.nanoTime();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n"
                + "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"batch.jsonl\"\r\nContent-Type: application/jsonl\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/files"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(30))
                .POST(
                    HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(head),
                        HttpRequest.BodyPublishers.ofFile(jsonl),
                        HttpRequest.BodyPublishers.ofByteArray(tail)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "file upload failed: " + resp.statusCode() + " " + resp.body());
    }
    return mapper.readTree(resp.body()).path("id").asText();
  }

  /**
   * Creates a batch job, tagged with our own batch id.
   *
   * <p>The tag is what makes a crash between "provider accepted" and "we recorded it" resolvable by
   * asking rather than assuming — the difference between adopting an orphan and paying for the same
   * pages twice.
   */
  public String createBatch(String inputFileId, String model, String endpoint, long ourBatchId)
      throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "input_files",
                List.of(inputFileId),
                "model",
                model,
                "endpoint",
                endpoint,
                "metadata",
                Map.of("archiver_batch_id", String.valueOf(ourBatchId))));
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/batch/jobs"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "batch create failed: " + resp.statusCode() + " " + resp.body());
    }
    return mapper.readTree(resp.body()).path("id").asText();
  }

  /** Current provider state for a batch, or null if it could not be read. */
  public JsonNode pollBatch(String providerJobId) {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/v1/batch/jobs/" + providerJobId))
                  .header("Authorization", "Bearer " + apiKey)
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() == 200 ? mapper.readTree(resp.body()) : null;
    } catch (Exception e) {
      log.warn("Polling provider batch {} failed", providerJobId, e);
      return null;
    }
  }

  /** Streams a batch's result file. The caller must close it. */
  public InputStream downloadOutput(String outputFileId) throws Exception {
    HttpResponse<InputStream> resp =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/files/" + outputFileId + "/content"))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException("output download failed: " + resp.statusCode());
    }
    return resp.body();
  }

  /**
   * Finds a provider batch carrying our tag, for adopting an unconfirmed submission.
   *
   * <p>Matched client-side: the provider returns metadata in listings but rejects it as a query
   * filter, so server-side filtering is not available however sensible it would be.
   */
  public String findByTag(long ourBatchId) {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/v1/batch/jobs?page_size=100"))
                  .header("Authorization", "Bearer " + apiKey)
                  .timeout(Duration.ofSeconds(60))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) return null;
      for (JsonNode n : mapper.readTree(resp.body()).path("data")) {
        if (String.valueOf(ourBatchId)
            .equals(n.path("metadata").path("archiver_batch_id").asText(null))) {
          return n.path("id").asText(null);
        }
      }
    } catch (Exception e) {
      log.warn("Could not list provider batches while reconciling {}", ourBatchId, e);
    }
    return null;
  }
}
