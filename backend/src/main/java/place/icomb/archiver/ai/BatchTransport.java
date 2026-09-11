package place.icomb.archiver.ai;

import java.nio.file.Path;
import java.util.List;

/**
 * How a provider's batch of requests is submitted, waited on and collected.
 *
 * <p>Separate from the capability interfaces because it is the provider's mechanics, not the
 * model's behaviour: Mistral's file upload and job polling are the same whether the batch holds OCR
 * pages or translations.
 *
 * <p>A provider with no batch API implements this by running the requests inline and returning
 * their results immediately. That keeps one code path: the orchestrator's claiming, accounting,
 * rate limiting and restart recovery are inherited rather than reimplemented per provider, which is
 * where the double-billing and orphan-adoption guards live.
 */
public interface BatchTransport {

  /** Provider family this transport speaks for. */
  String provider();

  /**
   * Submits a prepared JSONL file.
   *
   * @param requests the file, one JSON request per line, each carrying its {@code custom_id}
   * @param model the provider's model name
   * @param endpointPath the provider path the batch targets
   * @param tag an identifier written into the provider's metadata so an unconfirmed submission can
   *     be recognised on restart instead of being paid for twice
   * @return the provider's job identifier
   */
  String submit(Path requests, String model, String endpointPath, long tag) throws Exception;

  /** Finds a submission by its tag, for a batch whose outcome was never recorded. */
  String findByTag(long tag) throws Exception;

  /** Where a submission has got to. */
  Status poll(String providerJobId) throws Exception;

  /** The results of a finished submission, one line per request. */
  List<String> results(String outputFileId) throws Exception;

  /** Asks the provider to abandon a submission. */
  void cancel(String providerJobId) throws Exception;

  /**
   * A submission's progress.
   *
   * @param done whether the provider has finished with it
   * @param succeeded requests that produced a result
   * @param failed requests the provider rejected
   * @param outputFileId where the results are, once done
   */
  record Status(boolean done, int succeeded, int failed, String outputFileId) {}
}
