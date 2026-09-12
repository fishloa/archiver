package place.icomb.archiver.ai;

/**
 * The OCR model, described by one database row.
 *
 * <p>Everything here was an environment variable — which model, which endpoint, how many pages and
 * bytes may go in one batch, how fast to feed the provider. The admin page listed the OCR rows and
 * let an operator reorder and disable them, and none of it had any effect, because nothing read
 * them. Turning OCR off, or moving it to another provider, meant editing the compose file and
 * redeploying.
 *
 * <p>The credential is still an environment variable: a provider key in the catalogue database
 * would be readable by anything that can read the catalogue.
 */
public class RegistryOcr {

  private final AiRegistry.Registration registration;
  private final String apiKey;

  public RegistryOcr(AiRegistry.Registration registration, String apiKey) {
    this.registration = registration;
    this.apiKey = apiKey;
  }

  public String id() {
    return registration.id();
  }

  public String model() {
    return registration.model();
  }

  public String baseUrl() {
    return registration.baseUrl();
  }

  public String apiKey() {
    return apiKey;
  }

  public String endpointPath() {
    return registration.endpointPath() != null ? registration.endpointPath() : "/v1/ocr";
  }

  /** Pages per submission. */
  public int maxBatchPages() {
    return registration.maxBatchSize();
  }

  /**
   * Bytes per submission.
   *
   * <p>Page images run from 32 kB to 7.7 MB, so a batch fills up by size long before it fills up by
   * count — the byte ceiling is the one that actually binds.
   */
  public long maxBatchBytes() {
    return registration.setting("maxBatchBytes", 209715200L);
  }

  public int pagesPerMinute() {
    return registration.setting("pagesPerMinute", 1250);
  }

  /** How often to look for work. Operational pacing, not a property of the model. */
  public long tickInterval() {
    return registration.setting("tickIntervalMs", 15000L);
  }

  /**
   * Whether OCR should run at all.
   *
   * <p>An enabled row with no credential is not "on": it would claim jobs and fail every one of
   * them against a provider that rejects the request.
   */
  public boolean isConfigured() {
    return apiKey != null
        && !apiKey.isBlank()
        && baseUrl() != null
        && !baseUrl().isBlank()
        && model() != null
        && !model().isBlank();
  }

  public String endpoint() {
    return baseUrl() + endpointPath();
  }
}
