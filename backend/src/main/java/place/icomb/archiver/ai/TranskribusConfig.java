package place.icomb.archiver.ai;

import java.util.Optional;
import org.springframework.core.env.Environment;

/**
 * The Transkribus HTR engine, described by one {@code ai_implementation} row.
 *
 * <p>Which model runs is data, not code. A row carries a default {@code htrId} and an optional
 * {@code htrByLang} map, so German, Czech and English pages can go to the model trained for each
 * while everything else falls back to a multilingual one. Moving to Text Titan II on a paid plan is
 * enabling a different row.
 *
 * <p>The credential stays in the environment. Transkribus authenticates against READCOOP's Keycloak
 * with a password grant, so the row names the variables and the deployment holds the values.
 */
public class TranskribusConfig {

  /** Transkribus bills one credit per page, whatever the model. */
  public static final String JOB_KIND = "ocr_page_transkribus";

  /**
   * How long a row read from the registry is trusted before it is read again.
   *
   * <p>Short enough that changing a model or an endpoint takes effect while the archive is running,
   * long enough that a page's dozen accessor calls are one query rather than a dozen.
   */
  private static final long REFRESH_MS = 10_000;

  /**
   * What the config speaks for when the registry holds no Transkribus row.
   *
   * <p>A row can be added, disabled or deleted while the archive runs, so "no engine" has to be an
   * answer rather than a NullPointerException from a scheduled task.
   */
  private static final AiRegistry.Registration NONE =
      new AiRegistry.Registration(
          null,
          AiCapability.OCR,
          "transkribus",
          null,
          null,
          null,
          null,
          1,
          Integer.MAX_VALUE,
          false,
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());

  private final AiRegistry registry;
  private final Environment environment;
  private final AiRegistry.Registration fixed;

  private volatile AiRegistry.Registration cached;
  private volatile long cachedUntil;

  /**
   * A config bound to one row, as it was when this was built.
   *
   * <p>For a caller that has already chosen a row and wants it to stay chosen for the length of one
   * request.
   */
  public TranskribusConfig(AiRegistry.Registration registration, Environment environment) {
    this.registry = null;
    this.environment = environment;
    this.fixed = registration;
  }

  /**
   * A config that re-reads its row from the registry.
   *
   * <p>The engine is data, so it has to be changeable without a restart: the worker that lives for
   * the life of the process used to hold whatever row existed at boot, which meant repointing a
   * model, an endpoint or a credential took a deployment to land. It also meant that enabling a
   * Transkribus row in a system that started without one did nothing at all.
   */
  public TranskribusConfig(AiRegistry registry, Environment environment) {
    this.registry = registry;
    this.environment = environment;
    this.fixed = null;
  }

  /** The row this config speaks for, or {@link #NONE} when the registry currently holds none. */
  private AiRegistry.Registration registration() {
    if (fixed != null) {
      return fixed;
    }
    long now = System.currentTimeMillis();
    AiRegistry.Registration current = cached;
    if (current != null && now < cachedUntil) {
      return current;
    }
    AiRegistry.Registration found =
        registry.forCapability(AiCapability.OCR).stream()
            .filter(r -> "transkribus".equals(r.provider()))
            .findFirst()
            .orElse(NONE);
    cached = found;
    cachedUntil = now + REFRESH_MS;
    return found;
  }

  /** Drops the cached row so the next read goes to the registry; for tests and for admin edits. */
  public void forgetCachedRow() {
    cachedUntil = 0L;
  }

  /** Whether a Transkribus row is registered and enabled right now. */
  public boolean isRegistered() {
    return registration().id() != null;
  }

  /** The credential is never stored in the row: it names an environment variable. */
  public String password() {
    AiRegistry.Registration r = registration();
    return r.credentialEnv() == null ? null : environment.getProperty(r.credentialEnv());
  }

  public String username() {
    return environment.getProperty(registration().setting("usernameEnv", "TRANSKRIBUS_USERNAME"));
  }

  public String id() {
    return registration().id();
  }

  /** The human name of the model, for the dashboard and for {@code page_text.engine}. */
  public String model() {
    return registration().model();
  }

  public String baseUrl() {
    return registration().baseUrl();
  }

  public String processesPath() {
    return registration().endpointPath() != null ? registration().endpointPath() : "/processes";
  }

  public String tokenUrl() {
    return registration()
        .setting(
            "tokenUrl",
            "https://account.readcoop.eu/auth/realms/readcoop/protocol/openid-connect/token");
  }

  public String clientId() {
    return registration().setting("clientId", "processing-api-client");
  }

  /** {@code built-in} enables Transkribus's own language model over the raw HTR output. */
  public String languageModel() {
    return registration().setting("languageModel", "built-in");
  }

  /**
   * The handwriting model for a content language, falling back to the row's default.
   *
   * @param lang ISO 639-1 code from {@code record.lang}, or null
   */
  public int htrId(String lang) {
    return byLang("htrByLang", lang).orElseGet(() -> registration().setting("htrId", 51170));
  }

  /**
   * The typescript model for a content language. Used only when a caller says the page is print —
   * nothing classifies pages yet, so this is never chosen automatically.
   */
  public int printHtrId(String lang) {
    return byLang("printHtrByLang", lang)
        .orElseGet(() -> registration().setting("printHtrId", 37545));
  }

  private Optional<Integer> byLang(String settingKey, String lang) {
    if (lang == null || lang.isBlank()) {
      return Optional.empty();
    }
    var node = registration().settings().path(settingKey).path(lang.toLowerCase());
    return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node.asInt());
  }

  /**
   * Pages this account may transcribe per calendar month.
   *
   * <p>Read from the row rather than assumed: the free allowance is 50 and the Scholar monthly plan
   * adds 150. The worker counts what it has already spent and stops, because overshooting does not
   * queue — it fails, and on a credit-metered service a runaway loop is a bill rather than a delay.
   */
  public int monthlyCredits() {
    return registration().setting("monthlyCredits", 50);
  }

  /** Which Transkribus API to drive: {@code trpserver} (the classic one) or {@code metagrapho}. */
  public String api() {
    return registration().setting("api", "trpserver");
  }

  /** The collection documents are uploaded into; 0 means "the account's first collection". */
  public int collectionId() {
    return registration().setting("collId", 0);
  }

  /**
   * Title given to the one-page documents this creates, so they are identifiable in the web app.
   */
  public String documentTitle() {
    return registration().setting("documentTitle", "archiver");
  }

  public long pollIntervalMs() {
    return registration().setting("pollIntervalMs", 5000L);
  }

  /** How long to wait for one page before giving up. Transkribus queues behind other users. */
  public long maxPollMs() {
    return registration().setting("maxPollMs", 900000L);
  }

  public long tickIntervalMs() {
    return registration().setting("tickIntervalMs", 20000L);
  }

  /**
   * Whether this engine can run.
   *
   * <p>An enabled row with no password would claim jobs and fail every one of them, and each
   * failure is a wasted page from a small monthly allowance.
   */
  public boolean isConfigured() {
    return isRegistered()
        && notBlank(password())
        && notBlank(username())
        && notBlank(baseUrl())
        && notBlank(tokenUrl())
        && notBlank(clientId());
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
