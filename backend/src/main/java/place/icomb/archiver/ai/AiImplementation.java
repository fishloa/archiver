package place.icomb.archiver.ai;

/**
 * One model, from one provider, doing one job.
 *
 * <p>Everything is addressed as a batch. A provider with no batch API is not a special case: its
 * implementation reports {@link #maxBatchSize()} of 1 and its transport runs the request inline, so
 * the orchestrator's claiming, accounting, rate limiting and restart recovery apply unchanged
 * whether the work went to Mistral's batch endpoint or straight down a socket.
 *
 * <p>{@link #id()} is what the database stores and the admin UI shows, so it must stay stable:
 * changing it orphans the rank rows and the provider_batch history that refer to it.
 */
public interface AiImplementation {

  /** Stable identifier, e.g. {@code mistral:mistral-medium-latest}. */
  String id();

  /** Provider family, e.g. {@code mistral}, for grouping and credential lookup. */
  String provider();

  /** The model as the provider names it, e.g. {@code mistral-medium-latest}. */
  String model();

  AiCapability capability();

  /**
   * Largest number of items this implementation accepts in one submission.
   *
   * <p>1 means the provider has no batch API and each item is sent on its own.
   */
  int maxBatchSize();

  /**
   * Whether this implementation can currently be used.
   *
   * <p>False when a credential or endpoint is missing. An unconfigured implementation is skipped
   * rather than tried and failed, so a half-filled configuration cannot quietly send work to a
   * provider that will reject it.
   */
  boolean isConfigured();

  /** Human-readable endpoint, for the pipeline dashboard. */
  String endpoint();
}
