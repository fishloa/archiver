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

  private final AiRegistry.Registration registration;
  private final String password;
  private final String username;

  public TranskribusConfig(AiRegistry.Registration registration, Environment environment) {
    this.registration = registration;
    this.password =
        registration.credentialEnv() == null
            ? null
            : environment.getProperty(registration.credentialEnv());
    this.username =
        environment.getProperty(registration.setting("usernameEnv", "TRANSKRIBUS_USERNAME"));
  }

  public String id() {
    return registration.id();
  }

  /** The human name of the model, for the dashboard and for {@code page_text.engine}. */
  public String model() {
    return registration.model();
  }

  public String baseUrl() {
    return registration.baseUrl();
  }

  public String processesPath() {
    return registration.endpointPath() != null ? registration.endpointPath() : "/processes";
  }

  public String tokenUrl() {
    return registration.setting(
        "tokenUrl",
        "https://account.readcoop.eu/auth/realms/readcoop/protocol/openid-connect/token");
  }

  public String clientId() {
    return registration.setting("clientId", "processing-api-client");
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  /** {@code built-in} enables Transkribus's own language model over the raw HTR output. */
  public String languageModel() {
    return registration.setting("languageModel", "built-in");
  }

  /**
   * The handwriting model for a content language, falling back to the row's default.
   *
   * @param lang ISO 639-1 code from {@code record.lang}, or null
   */
  public int htrId(String lang) {
    return byLang("htrByLang", lang).orElseGet(() -> registration.setting("htrId", 51170));
  }

  /**
   * The typescript model for a content language. Used only when a caller says the page is print —
   * nothing classifies pages yet, so this is never chosen automatically.
   */
  public int printHtrId(String lang) {
    return byLang("printHtrByLang", lang)
        .orElseGet(() -> registration.setting("printHtrId", 37545));
  }

  private Optional<Integer> byLang(String settingKey, String lang) {
    if (lang == null || lang.isBlank()) {
      return Optional.empty();
    }
    var node = registration.settings().path(settingKey).path(lang.toLowerCase());
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
    return registration.setting("monthlyCredits", 50);
  }

  /** Which Transkribus API to drive: {@code trpserver} (the classic one) or {@code metagrapho}. */
  public String api() {
    return registration.setting("api", "trpserver");
  }

  /** The collection documents are uploaded into; 0 means "the account's first collection". */
  public int collectionId() {
    return registration.setting("collId", 0);
  }

  /**
   * Title given to the one-page documents this creates, so they are identifiable in the web app.
   */
  public String documentTitle() {
    return registration.setting("documentTitle", "archiver");
  }

  public long pollIntervalMs() {
    return registration.setting("pollIntervalMs", 5000L);
  }

  /** How long to wait for one page before giving up. Transkribus queues behind other users. */
  public long maxPollMs() {
    return registration.setting("maxPollMs", 900000L);
  }

  public long tickIntervalMs() {
    return registration.setting("tickIntervalMs", 20000L);
  }

  /**
   * Whether this engine can run.
   *
   * <p>An enabled row with no password would claim jobs and fail every one of them, and each
   * failure is a wasted page from a small monthly allowance.
   */
  public boolean isConfigured() {
    return notBlank(password)
        && notBlank(username)
        && notBlank(baseUrl())
        && notBlank(tokenUrl())
        && notBlank(clientId());
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
