package place.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One submission to an OCR provider's batch API.
 *
 * <p>Holds all in-flight state, so a backend restart resumes from the table rather than from
 * memory. {@code status} moves submitting → submitted → collected, or → failed.
 */
@Table("ocr_batch")
public class OcrBatch {

  /** Claimed locally, provider not yet confirmed. A crash here is reconcilable. */
  public static final String SUBMITTING = "submitting";

  /** The provider has it; awaiting results. */
  public static final String SUBMITTED = "submitted";

  /** Every page accounted for, success or failure. */
  public static final String COLLECTED = "collected";

  /** Abandoned; its pages were released or failed individually. */
  public static final String FAILED = "failed";

  @Id private Long id;
  private String status;
  private int pageCount;
  private String inputFileId;
  private String providerJobId;
  private String outputFileId;
  private Integer succeeded;
  private Integer failed;
  private String error;
  private Instant createdAt;
  private Instant submittedAt;
  private Instant lastPolledAt;
  private Instant collectedAt;

  public Long getId() {
    return id;
  }

  public String getStatus() {
    return status;
  }

  public int getPageCount() {
    return pageCount;
  }

  public String getInputFileId() {
    return inputFileId;
  }

  public String getProviderJobId() {
    return providerJobId;
  }

  public String getOutputFileId() {
    return outputFileId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }
}
