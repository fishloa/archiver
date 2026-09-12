package place.icomb.archiver.ai;

import java.util.List;
import place.icomb.archiver.service.EmbeddingClient;

/**
 * The embedding model, described by one database row.
 *
 * <p>Both sides of retrieval have to agree, and getting that wrong raises no error: the query side
 * prefixes with Qwen3's instruction, the passage side prefixes nothing, and both must reach the
 * same model at the same width or search silently gets worse. That agreement used to rest on three
 * separate properties — {@code archiver.embed.tei-url}, {@code archiver.embed.model} and {@code
 * archiver.embed.query-prefix} — which nothing forced to describe the same model. They already
 * have, for two days, described different ones.
 *
 * <p>One row now supplies all of it, so a mismatch requires editing a single record into an
 * inconsistent state rather than merely forgetting to edit a second place.
 */
public class RegistryEmbedder implements Embedder {

  private final AiRegistry.Registration registration;
  private final String apiKey;
  private final EmbeddingClient client;

  public RegistryEmbedder(AiRegistry.Registration registration, String apiKey) {
    this.registration = registration;
    this.apiKey = apiKey;
    this.client = new EmbeddingClient(baseUrl(), apiKey, registration.model(), dimensions());
  }

  /** The base URL a request goes to, including the provider's path prefix. */
  public String baseUrl() {
    return registration.baseUrl();
  }

  public String apiKey() {
    return apiKey;
  }

  public String endpointPath() {
    return registration.endpointPath() != null ? registration.endpointPath() : "/embeddings";
  }

  /** The passage-side client, for the worker that embeds chunks. */
  public EmbeddingClient client() {
    return client;
  }

  @Override
  public String id() {
    return registration.id();
  }

  @Override
  public String provider() {
    return registration.provider();
  }

  @Override
  public String model() {
    return registration.model();
  }

  @Override
  public int maxBatchSize() {
    return registration.maxBatchSize();
  }

  @Override
  public int dimensions() {
    // 1024 because pgvector's HNSW index refuses more than 2000, and Qwen3 is Matryoshka-trained
    // so truncating 4096 to 1024 measured no quality loss.
    return registration.setting("dimensions", 1024);
  }

  @Override
  public String queryPrefix() {
    return registration.setting("queryPrefix", "");
  }

  @Override
  public boolean isConfigured() {
    return baseUrl() != null && !baseUrl().isBlank() && apiKey != null && !apiKey.isBlank();
  }

  @Override
  public String endpoint() {
    return baseUrl() + endpointPath();
  }

  @Override
  public List<float[]> embed(List<String> texts) throws Exception {
    return client.embed(texts);
  }
}
