package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embeds text through an OpenAI-compatible {@code /embeddings} endpoint.
 *
 * <p>Queries and passages must be embedded by the same model, through the same configuration, or
 * retrieval degrades with no error raised anywhere. This client is therefore the only place the
 * backend calls an embedding provider, and {@link SemanticSearchController} reads the same
 * properties for its query side.
 */
public class EmbeddingClient {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int dimensions;
  private final ResilientHttpClient http = ResilientHttpClient.builder().build();

  public EmbeddingClient(String baseUrl, String apiKey, String model, int dimensions) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.dimensions = dimensions;
  }

  public String model() {
    return model;
  }

  public String providerUrl() {
    return baseUrl;
  }

  public boolean isConfigured() {
    return baseUrl != null && !baseUrl.isBlank();
  }

  /**
   * Embeds a batch, halving it on a 413/422 rather than failing.
   *
   * <p>Chunk sizes are bounded by characters, not tokens, and a page of dense abbreviations can
   * exceed the provider's token limit even inside a nominally small batch. Halving degrades
   * throughput; failing would leave the record unsearchable.
   */
  public List<float[]> embed(List<String> texts) throws Exception {
    if (texts.isEmpty()) {
      return List.of();
    }
    try {
      return request(texts);
    } catch (TooLarge e) {
      if (texts.size() == 1) {
        throw new IllegalStateException(
            "Provider rejected a single chunk of " + texts.get(0).length() + " characters", e);
      }
      log.warn("Embedding batch of {} rejected as too large; splitting", texts.size());
      int mid = texts.size() / 2;
      List<float[]> out = new ArrayList<>(embed(texts.subList(0, mid)));
      out.addAll(embed(texts.subList(mid, texts.size())));
      return out;
    }
  }

  private List<float[]> request(List<String> texts) throws Exception {
    String body =
        MAPPER.writeValueAsString(Map.of("model", model, "input", texts, "dimensions", dimensions));

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }

    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() == 413 || response.statusCode() == 422) {
      throw new TooLarge(response.body());
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Embedding provider returned " + response.statusCode() + ": " + response.body());
    }

    JsonNode data = MAPPER.readTree(response.body()).path("data");
    // Ordered by the provider's own index: trusting arrival order would attach an embedding to
    // the wrong chunk, which is undetectable afterwards.
    float[][] byIndex = new float[texts.size()][];
    for (JsonNode item : data) {
      int index = item.path("index").asInt(-1);
      if (index < 0 || index >= texts.size()) {
        throw new IllegalStateException("Embedding response index out of range: " + index);
      }
      JsonNode vector = item.path("embedding");
      float[] values = new float[vector.size()];
      for (int i = 0; i < vector.size(); i++) {
        values[i] = (float) vector.get(i).asDouble();
      }
      byIndex[index] = values;
    }
    List<float[]> out = new ArrayList<>(texts.size());
    for (int i = 0; i < texts.size(); i++) {
      if (byIndex[i] == null) {
        throw new IllegalStateException("Embedding provider returned no vector for chunk " + i);
      }
      out.add(byIndex[i]);
    }
    return out;
  }

  /** The provider refused the batch for size; the caller splits and retries. */
  static class TooLarge extends Exception {
    TooLarge(String message) {
      super(message);
    }
  }
}
