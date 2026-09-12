package place.icomb.archiver.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The provider protocols this build actually implements.
 *
 * <p>{@code ai_implementation.provider} used to be free text, and nothing read it: every
 * translation row became a {@link ChatTranslator} submitted through Mistral's batch API regardless
 * of what the row said. So the admin page would let you point translation at DeepInfra or a local
 * server, and the system would fire {@code POST /v1/files} and {@code POST /v1/batch/jobs} at it
 * and fail every job. V9 seeds exactly such a row.
 *
 * <p>A provider is therefore a protocol, not a label, and only protocols with an adapter behind
 * them may be chosen. This enum is the single source of truth for that: the admin API serves it,
 * the UI renders its form from it, and the runtime dispatches on it. Adding a provider means adding
 * an entry here and an adapter — never editing the frontend.
 */
public enum ProviderApi {

  /**
   * Mistral's own API: chat completions and OCR, submitted through the batch endpoints. The only
   * provider here that runs work asynchronously.
   */
  MISTRAL_BATCH(
      "mistral-batch",
      "Mistral (batch API)",
      BatchStyle.ASYNC_JOB,
      "https://api.mistral.ai",
      Map.of(
          AiCapability.TRANSLATION, "/v1/chat/completions",
          AiCapability.OCR, "/v1/ocr"),
      1,
      50_000,
      List.of(
          Setting.integer("pagesPerMinute", "Pages per minute", "Rate fed to the provider.", 1250)
              .forCapabilities(AiCapability.OCR),
          Setting.integer(
                  "maxBatchBytes",
                  "Max batch bytes",
                  "Page images run from 32 kB to 7.7 MB, so a batch fills up by size long before"
                      + " it fills up by count. This is the ceiling that actually binds.",
                  209_715_200)
              .forCapabilities(AiCapability.OCR),
          Setting.integer(
                  "tickIntervalMs", "Tick interval (ms)", "How often to look for work.", 15000)
              .forCapabilities(AiCapability.OCR),
          Setting.choice(
                  "tier",
                  "Tier",
                  "Which translation quality this model serves. Bulk runs across the whole"
                      + " archive; best is the on-demand upgrade, queued as a separate job kind.",
                  "bulk",
                  "bulk",
                  "best")
              .forCapabilities(AiCapability.TRANSLATION))),

  /**
   * Any OpenAI-compatible endpoint: DeepInfra, a local vLLM or Ollama server, OpenAI itself.
   * Embeddings take an array in one request; chat is one document at a time.
   */
  OPENAI(
      "openai",
      "OpenAI-compatible",
      BatchStyle.INLINE_ARRAY,
      "",
      Map.of(
          AiCapability.EMBEDDING, "/embeddings",
          AiCapability.TRANSLATION, "/chat/completions"),
      1,
      512,
      List.of(
          Setting.integer(
                  "dimensions",
                  "Dimensions",
                  "Vector width. pgvector's HNSW index refuses more than 2000.",
                  1024)
              .forCapabilities(AiCapability.EMBEDDING),
          Setting.text(
                  "queryPrefix",
                  "Query prefix",
                  "Instruction prefix this model expects on a query, applied to queries only."
                      + " Qwen3 wants one; bge-m3 does not, and sending Qwen3's to it degrades"
                      + " every search while raising no error.",
                  "")
              .forCapabilities(AiCapability.EMBEDDING),
          Setting.choice(
                  "tier",
                  "Tier",
                  "Which translation quality this model serves. Bulk runs across the whole"
                      + " archive; best is the on-demand upgrade, queued as a separate job kind.",
                  "bulk",
                  "bulk",
                  "best")
              .forCapabilities(AiCapability.TRANSLATION)));

  private final String id;
  private final String label;
  private final BatchStyle batchStyle;
  private final String defaultBaseUrl;
  private final Map<AiCapability, String> endpointPaths;
  private final int minBatchSize;
  private final int maxBatchSize;
  private final List<Setting> settings;

  ProviderApi(
      String id,
      String label,
      BatchStyle batchStyle,
      String defaultBaseUrl,
      Map<AiCapability, String> endpointPaths,
      int minBatchSize,
      int maxBatchSize,
      List<Setting> settings) {
    this.id = id;
    this.label = label;
    this.batchStyle = batchStyle;
    this.defaultBaseUrl = defaultBaseUrl;
    this.endpointPaths = endpointPaths;
    this.minBatchSize = minBatchSize;
    this.maxBatchSize = batchStyle == BatchStyle.SINGLE ? 1 : maxBatchSize;
    this.settings = settings;
  }

  public String id() {
    return id;
  }

  public BatchStyle batchStyle() {
    return batchStyle;
  }

  public boolean supports(AiCapability capability) {
    return endpointPaths.containsKey(capability);
  }

  public String endpointPathFor(AiCapability capability) {
    return endpointPaths.get(capability);
  }

  public int minBatchSize() {
    return minBatchSize;
  }

  public int maxBatchSize() {
    return maxBatchSize;
  }

  /** The settings this provider takes for one capability. */
  public List<Setting> settingsFor(AiCapability capability) {
    return settings.stream().filter(x -> x.appliesTo(capability)).toList();
  }

  /** Looks up a provider by the id stored in the database. */
  public static Optional<ProviderApi> byId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    for (ProviderApi p : values()) {
      if (p.id.equalsIgnoreCase(id)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  /**
   * One configurable value a provider needs, described well enough for a form to be built from it
   * without the frontend knowing what any particular provider is.
   */
  public record Setting(
      String key,
      String label,
      String type,
      String help,
      Object defaultValue,
      List<String> options,
      List<AiCapability> capabilities) {

    static Setting text(String key, String label, String help, String defaultValue) {
      return new Setting(key, label, "text", help, defaultValue, List.of(), List.of());
    }

    static Setting integer(String key, String label, String help, int defaultValue) {
      return new Setting(key, label, "integer", help, defaultValue, List.of(), List.of());
    }

    /**
     * A value that may only be one of a fixed set.
     *
     * <p>Typed as free text it is an invitation to misspell: "Best" or "premium" in the tier field
     * reads as a tier that is not "best", and the row then silently serves bulk work. The choices
     * belong to the backend because it is the backend that acts on them.
     */
    static Setting choice(
        String key, String label, String help, String defaultValue, String... options) {
      return new Setting(key, label, "choice", help, defaultValue, List.of(options), List.of());
    }

    Setting forCapabilities(AiCapability... caps) {
      return new Setting(key, label, type, help, defaultValue, options, List.of(caps));
    }

    /** Empty capabilities means the setting applies to every capability the provider serves. */
    public boolean appliesTo(AiCapability capability) {
      return capabilities.isEmpty() || capabilities.contains(capability);
    }

    Map<String, Object> describe() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", key);
      m.put("label", label);
      m.put("type", type);
      m.put("help", help);
      m.put("default", defaultValue);
      m.put("options", options);
      return m;
    }
  }

  /**
   * The whole registry, as the admin UI consumes it.
   *
   * <p>Shaped so a form can be rendered from it with no provider-specific knowledge in the
   * frontend: which capabilities each provider can serve, what endpoint each one defaults to, what
   * a batch means for it, and which extra settings it takes.
   */
  public static List<Map<String, Object>> describeAll() {
    return java.util.Arrays.stream(values()).map(ProviderApi::describe).toList();
  }

  private Map<String, Object> describe() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("label", label);
    m.put("batchStyle", batchStyle.name());
    m.put("defaultBaseUrl", defaultBaseUrl);
    m.put("minBatchSize", minBatchSize);
    m.put("maxBatchSize", maxBatchSize);

    Map<String, Object> perCapability = new LinkedHashMap<>();
    for (AiCapability capability : AiCapability.values()) {
      if (!supports(capability)) {
        continue;
      }
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("endpointPath", endpointPathFor(capability));
      c.put(
          "settings",
          settings.stream().filter(s -> s.appliesTo(capability)).map(Setting::describe).toList());
      perCapability.put(capability.name(), c);
    }
    m.put("capabilities", perCapability);
    return m;
  }
}
