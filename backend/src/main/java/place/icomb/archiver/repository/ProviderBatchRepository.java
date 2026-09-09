package place.icomb.archiver.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.ProviderBatch;

/** Persistence for OCR provider batches. Keeps the batch worker free of SQL. */
public interface ProviderBatchRepository extends CrudRepository<ProviderBatch, Long> {

  @Query(
      """
      INSERT INTO provider_batch (status, page_count, job_kind)
      VALUES ('submitting', 0, :jobKind) RETURNING id
      """)
  Long createSubmitting(@Param("jobKind") String jobKind);

  @Modifying
  @Query("UPDATE provider_batch SET input_file_id = :fileId, page_count = :pages WHERE id = :id")
  void recordInput(@Param("id") Long id, @Param("fileId") String fileId, @Param("pages") int pages);

  @Modifying
  @Query(
      """
      UPDATE provider_batch SET provider_job_id = :providerJobId, status = 'submitted',
                           submitted_at = now()
      WHERE id = :id
      """)
  void markSubmitted(@Param("id") Long id, @Param("providerJobId") String providerJobId);

  @Modifying
  @Query("UPDATE provider_batch SET status = 'failed', error = :error WHERE id = :id")
  void markFailed(@Param("id") Long id, @Param("error") String error);

  @Modifying
  @Query(
      """
      UPDATE provider_batch SET succeeded = :succeeded, failed = :failed,
                           output_file_id = :outputFileId, last_polled_at = now()
      WHERE id = :id
      """)
  void recordPoll(
      @Param("id") Long id,
      @Param("succeeded") int succeeded,
      @Param("failed") int failed,
      @Param("outputFileId") String outputFileId);

  @Modifying
  @Query("UPDATE provider_batch SET status = 'collected', collected_at = now() WHERE id = :id")
  void markCollected(@Param("id") Long id);

  @Modifying
  @Query("DELETE FROM provider_batch WHERE id = :id")
  void deleteEmpty(@Param("id") Long id);

  @Query("SELECT * FROM provider_batch WHERE status = :status ORDER BY id")
  List<ProviderBatch> findByStatus(
      @Param("status") String status, @Param("jobKind") String jobKind);

  /** Submitted batches with results waiting to be collected. */
  @Query(
      """

      SELECT * FROM provider_batch WHERE status = 'submitted' AND output_file_id IS NOT NULL AND job_kind = :jobKind ORDER BY id
      """)
  List<ProviderBatch> findCollectable(@Param("jobKind") String jobKind);

  /** Submitted batches still being worked by the provider. */
  @Query(
      """

      SELECT * FROM provider_batch WHERE status = 'submitted' AND provider_job_id IS NOT NULL AND job_kind = :jobKind ORDER BY id
      """)
  List<ProviderBatch> findPollable(@Param("jobKind") String jobKind);

  /** Batches the provider has held past any plausible turnaround. */
  @Query(
      "SELECT * FROM provider_batch WHERE status = 'submitted' AND submitted_at < :cutoff AND job_kind = :jobKind")
  List<ProviderBatch> findTimedOut(
      @Param("cutoff") Instant cutoff, @Param("jobKind") String jobKind);

  /**
   * Pages handed to the provider in the last minute, for rate limiting.
   *
   * <p>Derived from the table rather than an in-memory counter so the limit survives a restart and
   * needs no coordination between instances.
   */
  @Query(
      """
      SELECT COALESCE(SUM(page_count), 0) FROM provider_batch
      WHERE submitted_at > now() - interval '60 seconds' AND job_kind = :jobKind
      """)
  int pagesSubmittedInLastMinute(@Param("jobKind") String jobKind);
}
