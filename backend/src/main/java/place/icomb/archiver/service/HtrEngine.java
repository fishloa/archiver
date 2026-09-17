package place.icomb.archiver.service;

/**
 * Transcribes one page of handwriting.
 *
 * <p>Two implementations, because Transkribus has two APIs and which one an account may use depends
 * on its plan. {@link TranskribusTrpClient} drives the classic TrpServer API, which every account
 * can reach; {@link TranskribusClient} drives the newer per-image Metagrapho API, which answered
 * HTTP 401 on a free account whose token carried only the "Transkribus User" role. The row in
 * {@code ai_implementation} chooses.
 */
public interface HtrEngine {

  /**
   * Reads one page.
   *
   * @param imageBytes the page image
   * @param modelId the provider's model id
   * @return the transcription, with the provider's own job reference for the audit trail
   */
  Result transcribe(byte[] imageBytes, int modelId) throws Exception;

  /**
   * One page's transcription.
   *
   * @param text the text exactly as the engine produced it
   * @param modelId the model that produced it — part of the provenance, since a free community
   *     model and a paid super model are not the same evidence
   * @param providerJobId the provider's process or job id, for chasing a bad page later
   * @param pageXml PAGE XML with line polygons and baselines, or null if it could not be fetched
   */
  record Result(String text, int modelId, String providerJobId, String pageXml) {}
}
