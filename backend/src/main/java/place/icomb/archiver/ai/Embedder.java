package place.icomb.archiver.ai;

import java.util.List;

/** Turns text into vectors. */
public interface Embedder extends AiImplementation {

  @Override
  default AiCapability capability() {
    return AiCapability.EMBEDDING;
  }

  /** Vector width. Queries and passages must agree, or retrieval degrades with no error. */
  int dimensions();

  /**
   * The instruction prefix this model expects on a query, or empty if it expects none.
   *
   * <p>Belongs to the implementation because it is a property of the model, not of the archive.
   * Qwen3 wants one and bge-m3 does not, and sending Qwen3's prefix to bge-m3 — which happened here
   * for two days — silently degrades every search while raising no error at all.
   */
  String queryPrefix();

  /**
   * Embeds a batch, in the order given.
   *
   * <p>Implementations must return one vector per input, positionally. A provider that returns
   * results out of order has to reorder them: attaching an embedding to the wrong chunk is
   * undetectable afterwards.
   */
  List<float[]> embed(List<String> texts) throws Exception;
}
