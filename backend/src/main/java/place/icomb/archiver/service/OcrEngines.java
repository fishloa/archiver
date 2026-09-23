package place.icomb.archiver.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;

/**
 * Which OCR engine reads a record's pages.
 *
 * <p>A record may name its own engine at ingest — handwriting to Transkribus, print to Mistral —
 * and falls back to the deployment default when it does not. Without this the choice was one
 * setting for the whole archive, so a handwritten file was read by the print engine and then had to
 * be found and re-OCR'd a page at a time.
 */
@Service
public class OcrEngines {

  private final AiRegistry registry;
  private final JdbcTemplate jdbcTemplate;
  private final String defaultEngine;

  public OcrEngines(
      AiRegistry registry,
      JdbcTemplate jdbcTemplate,
      @Value("${archiver.ocr.default-engine:ocr_page_mistral}") String defaultEngine) {
    this.registry = registry;
    this.jdbcTemplate = jdbcTemplate;
    this.defaultEngine = defaultEngine;
  }

  public String defaultEngine() {
    return defaultEngine;
  }

  /**
   * The job kinds a registered engine will claim.
   *
   * <p>The Transkribus rows carry their job kind in settings; the batch engines are named by their
   * own job kind, so a row without one is the default engine.
   *
   * <p>Enabled rows, not credentialled ones: an ingest may run long before the pages are read, and
   * a key that is absent this minute is a deployment matter, not a reason to refuse the record.
   * {@code reocr-page}, which starts work immediately, checks the credential as well.
   */
  public List<String> claimableKinds() {
    return registry.forCapability(AiCapability.OCR).stream()
        .map(r -> r.setting("jobKind", defaultEngine))
        .distinct()
        .toList();
  }

  /** Whether any configured engine claims this job kind. */
  public boolean claimable(String jobKind) {
    return jobKind != null && claimableKinds().contains(jobKind);
  }

  /**
   * The engine for a record: its own if it named one, otherwise the deployment default.
   *
   * <p>A record's engine is not re-validated here. It was checked when it was set, and a queued job
   * nothing can claim is better than a record that silently stops being OCR'd because a registry
   * row was disabled after the fact.
   */
  public String forRecord(Long recordId) {
    String named =
        jdbcTemplate.queryForObject(
            "SELECT ocr_engine FROM record WHERE id = ?", String.class, recordId);
    return named == null || named.isBlank() ? defaultEngine : named;
  }
}
