package place.icomb.archiver.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.RegistryEmbedder;

/**
 * The one embedding model this deployment uses.
 *
 * <p>Resolved from the registry so it is a database row an admin can change, not a redeploy. The
 * environment is kept as a fallback for a deployment whose row is missing, and credentials still
 * come from the environment either way — a key in the database would be readable by anything that
 * can read the catalogue.
 */
@Configuration
public class EmbeddingConfig {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

  @Bean
  public RegistryEmbedder embedder(
      AiRegistry registry,
      @Value("${archiver.embed.tei-url:}") String envUrl,
      @Value("${archiver.embed.tei-key:}") String envKey,
      @Value("${archiver.embed.model:}") String envModel,
      @Value("${archiver.embed.dimensions:1024}") int envDimensions,
      @Value("${archiver.embed.query-prefix:}") String envQueryPrefix) {

    var registered = registry.best(AiCapability.EMBEDDING);
    if (registered.isPresent()) {
      var r = registered.get();
      var embedder = new RegistryEmbedder(r, registry.credential(r));
      log.info(
          "Embedding from registry: {} ({} dims, batch {}, {})",
          embedder.model(),
          embedder.dimensions(),
          embedder.maxBatchSize(),
          embedder.endpoint());
      return embedder;
    }

    // No enabled row. Describe the environment as a registration so the rest of the system sees
    // one shape either way.
    ObjectNode settings = JsonNodeFactory.instance.objectNode();
    settings.put("dimensions", envDimensions);
    settings.put("queryPrefix", envQueryPrefix);
    var fallback =
        new AiRegistry.Registration(
            "env:" + (envModel == null || envModel.isBlank() ? "embedding" : envModel),
            AiCapability.EMBEDDING,
            "env",
            envModel,
            envUrl,
            "/embeddings",
            null,
            128,
            1,
            true,
            settings);
    log.warn(
        "No enabled EMBEDDING row; falling back to archiver.embed.* (model={}, dims={})",
        envModel,
        envDimensions);
    return new RegistryEmbedder(fallback, envKey);
  }
}
