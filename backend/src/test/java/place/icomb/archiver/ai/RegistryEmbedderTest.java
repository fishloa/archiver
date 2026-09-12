package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Queries and passages must reach the same model at the same width, with the instruction prefix on
 * the query side only. That agreement used to rest on three unrelated properties.
 */
class RegistryEmbedderTest {

  private AiRegistry.Registration registration(ObjectNode settings) {
    return new AiRegistry.Registration(
        "deepinfra:Qwen/Qwen3-Embedding-8B",
        AiCapability.EMBEDDING,
        "deepinfra",
        "Qwen/Qwen3-Embedding-8B",
        "https://api.deepinfra.com/v1/openai",
        "/embeddings",
        "EMBED_TEI_KEY",
        128,
        1,
        true,
        settings);
  }

  private ObjectNode settings(int dimensions, String prefix) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("dimensions", dimensions);
    s.put("queryPrefix", prefix);
    return s;
  }

  @Test
  void readsModelWidthAndPrefixFromTheOneRow() {
    var embedder =
        new RegistryEmbedder(registration(settings(1024, "Instruct: find it\nQuery: ")), "key");

    assertThat(embedder.model()).isEqualTo("Qwen/Qwen3-Embedding-8B");
    assertThat(embedder.dimensions()).isEqualTo(1024);
    assertThat(embedder.queryPrefix()).isEqualTo("Instruct: find it\nQuery: ");
    assertThat(embedder.endpoint()).isEqualTo("https://api.deepinfra.com/v1/openai/embeddings");
  }

  @Test
  void thePassageClientIsBuiltFromTheSameRowAsTheQuerySide() {
    var embedder = new RegistryEmbedder(registration(settings(1024, "Q: ")), "key");

    // The worker embeds through this client; the controller reads model()/dimensions() directly.
    // One row feeds both, so they cannot describe different models.
    assertThat(embedder.client().model()).isEqualTo(embedder.model());
  }

  @Test
  void defaultsToTheWidthPgvectorCanIndexWhenTheRowIsSilent() {
    ObjectNode empty = JsonNodeFactory.instance.objectNode();

    var embedder = new RegistryEmbedder(registration(empty), "key");

    // pgvector's HNSW refuses more than 2000 dimensions.
    assertThat(embedder.dimensions()).isEqualTo(1024);
    assertThat(embedder.dimensions()).isLessThan(2000);
  }

  @Test
  void aModelThatWantsNoPrefixGetsAnEmptyOneRatherThanNull() {
    // bge-m3 wants no instruction prefix. Sending Qwen3's to it degrades search silently.
    var embedder = new RegistryEmbedder(registration(JsonNodeFactory.instance.objectNode()), "key");

    assertThat(embedder.queryPrefix()).isEmpty();
  }

  @Test
  void isNotConfiguredWithoutACredential() {
    var embedder = new RegistryEmbedder(registration(settings(1024, "")), "");

    // An unconfigured implementation is skipped rather than tried and failed.
    assertThat(embedder.isConfigured()).isFalse();
  }

  @Test
  void isConfiguredWithAUrlAndAKey() {
    var embedder = new RegistryEmbedder(registration(settings(1024, "")), "key");

    assertThat(embedder.isConfigured()).isTrue();
  }
}
