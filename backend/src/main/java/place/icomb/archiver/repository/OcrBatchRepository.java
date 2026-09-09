package place.icomb.archiver.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.OcrBatch;

/** Persistence for OCR provider batches. Keeps the batch worker free of SQL. */
public interface OcrBatchRepository extends CrudRepository<OcrBatch, Long> {

  @Query("INSERT INTO ocr_batch (status, page_count) VALUES ('submitting', 0) RETURNING id")
  Long createSubmitting();

  @Modifying
  @Query("UPDATE ocr_batch SET input_file_id = :fileId, page_count = :pages WHERE id = :id")
  void recordInput(@Param("id") Long id, @Param("fileId") String fileId, @Param("pages") int pages);

  @Modifying
  @Query(
      """
      UPDATE ocr_batch SET provider_job_id = :providerJobId, status = 'submitted',
                           submitted_at = now()
      WHERE id = :id
      """)
  void markSubmitted(@Param("id") Long id, @Param("providerJobId") String providerJobId);

  @Modifying
  @Query("UPDATE ocr_batch SET status = 'failed', error = :error WHERE id = :id")
  void markFailed(@Param("id") Long id, @Param("error") String error);

  @Modifying
  @Query(
      """
      UPDATE ocr_batch SET succeeded = :succeeded, failed = :failed,
                           output_file_id = :outputFileId, last_polled_at = now()
      WHERE id = :id
      """)
  void recordPoll(
      @Param("id") Long id,
      @Param("succeeded") int succeeded,
      @Param("failed") int failed,
      @Param("outputFileId") String outputFileId);

  @Modifying
  @Query("UPDATE ocr_batch SET status = 'collected', collected_at = now() WHERE id = :id")
  void markCollected(@Param("id") Long id);

  @Modifying
  @Query("DELETE FROM ocr_batch WHERE id = :id")
  void deleteEmpty(@Param("id") Long id);

  @Query("SELECT * FROM ocr_batch WHERE status = :status ORDER BY id")
  List<OcrBatch> findByStatus(@Param("status") String status);

  /** Submitted batches with results waiting to be collected. */
  @Query(
      """
      SELECT * FROM ocr_batch
      WHERE status = 'submitted' AND output_file_id IS NOT NULL
      ORDER BY id
      """)
  List<OcrBatch> findCollectable();

  /** Submitted batches still being worked by the provider. */
  @Query(
      """
      SELECT * FROM ocr_batch
      WHERE status = 'submitted' AND provider_job_id IS NOT NULL
      ORDER BY id
      """)
  List<OcrBatch> findPollable();

  /** Batches the provider has held past any plausible turnaround. */
  @Query("SELECT * FROM ocr_batch WHERE status = 'submitted' AND submitted_at < :cutoff")
  List<OcrBatch> findTimedOut(@Param("cutoff") Instant cutoff);

  /**
   * Pages handed to the provider in the last minute, for rate limiting.
   *
   * <p>Derived from the table rather than an in-memory counter so the limit survives a restart and
   * needs no coordination between instances.
   */
  @Query(
      """
      SELECT COALESCE(SUM(page_count), 0) FROM ocr_batch
      WHERE submitted_at > now() - interval '60 seconds'
      """)
  int pagesSubmittedInLastMinute();
}
