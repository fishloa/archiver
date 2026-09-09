package place.icomb.archiver.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.Job;

@Repository
public interface JobRepository extends CrudRepository<Job, Long> {

  /**
   * Atomically claims the next pending job of the given kind. Uses FOR UPDATE SKIP LOCKED to allow
   * concurrent workers to claim different jobs without blocking each other.
   */
  @Query(
      """
      UPDATE job SET status = 'claimed', attempts = attempts + 1, started_at = now()
      WHERE id = (
          SELECT id FROM job
          WHERE kind = :kind AND status = 'pending'
          ORDER BY created_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 1
      )
      RETURNING *
      """)
  Optional<Job> findAndClaimNextJob(@Param("kind") String kind);

  /**
   * Claims as many pending jobs of a kind as fit within a byte budget, tagging them with a batch.
   *
   * <p>Sized by bytes rather than by count because page images range from 32 kB to 7.7 MB, so a
   * fixed count would either overshoot the provider's upload limit or waste most of the budget.
   * attachment.bytes is already known and base64 inflates by 4/3, so the batch sizes itself in one
   * statement instead of claiming blindly and releasing the overflow.
   *
   * <p>The first row always qualifies. Without that, a page larger than the whole budget would
   * exceed it on row one, never be selected, and sit pending forever — silently blocking its record
   * from ever completing.
   */
  @Query(
      """
      UPDATE job SET status = 'claimed', attempts = attempts + 1, started_at = now(),
                     batch_id = :batchId
      WHERE id IN (
          SELECT id FROM (
              SELECT j.id,
                     row_number() OVER (ORDER BY j.id) AS rn,
                     sum(ceil(a.bytes * 4.0 / 3.0)::bigint + 256)
                         OVER (ORDER BY j.id ROWS UNBOUNDED PRECEDING) AS running
              FROM job j
              JOIN page p ON p.id = j.page_id
              JOIN attachment a ON a.id = p.attachment_id
              WHERE j.kind = :kind AND j.status = 'pending' AND j.batch_id IS NULL
              ORDER BY j.id
              LIMIT :maxRows
          ) sized
          WHERE sized.running <= :maxBytes OR sized.rn = 1)
        AND status = 'pending'
      RETURNING *
      """)
  List<Job> claimBatch(
      @Param("kind") String kind,
      @Param("maxRows") int maxRows,
      @Param("maxBytes") long maxBytes,
      @Param("batchId") Long batchId);

  /**
   * Claims a fixed number of pending jobs for a batch.
   *
   * <p>For stages whose request payload is text rather than an image, where a byte budget would be
   * pointless — a page of markdown is a few kilobytes, so the count is what matters.
   */
  @Query(
      """
      UPDATE job SET status = 'claimed', attempts = attempts + 1, started_at = now(),
                     batch_id = :batchId
      WHERE id IN (
          SELECT id FROM job
          WHERE kind = :kind AND status = 'pending' AND batch_id IS NULL
          ORDER BY id
          LIMIT :maxRows)
        AND status = 'pending'
      RETURNING *
      """)
  List<Job> claimBatchByCount(
      @Param("kind") String kind, @Param("maxRows") int maxRows, @Param("batchId") Long batchId);

  /**
   * Returns a batch's jobs to the queue.
   *
   * <p>{@code restoreAttempt} undoes the claim's attempt increment, for the case where the provider
   * never received the work and so should not count against the page's retries.
   */
  @Modifying
  @Query(
      """
      UPDATE job SET status = 'pending', batch_id = NULL, started_at = NULL,
                     attempts = CASE WHEN :restoreAttempt THEN GREATEST(attempts - 1, 0)
                                     ELSE attempts END
      WHERE batch_id = :batchId AND status = 'claimed'
      """)
  int releaseBatch(@Param("batchId") Long batchId, @Param("restoreAttempt") boolean restoreAttempt);

  @Query("SELECT * FROM job WHERE batch_id = :batchId AND status = 'claimed'")
  List<Job> findClaimedInBatch(@Param("batchId") Long batchId);

  @Query("SELECT count(*) FROM job WHERE batch_id = :batchId AND status IN ('completed','failed')")
  int countSettledInBatch(@Param("batchId") Long batchId);

  @Query("SELECT count(*) FROM job WHERE id = :id AND status = 'claimed' AND batch_id = :batchId")
  int isStillClaimedBy(@Param("id") Long id, @Param("batchId") Long batchId);

  List<Job> findByRecordId(Long recordId);

  @Query("SELECT * FROM job WHERE record_id = :recordId AND kind = :kind AND status = :status")
  List<Job> findByRecordIdAndKindAndStatus(
      @Param("recordId") Long recordId, @Param("kind") String kind, @Param("status") String status);

  @Modifying
  @Query("UPDATE job SET status = :status, finished_at = now() WHERE id = :id")
  void updateStatus(@Param("id") Long id, @Param("status") String status);

  @Modifying
  @Query("UPDATE job SET status = 'failed', error = :error, finished_at = now() WHERE id = :id")
  void markFailed(@Param("id") Long id, @Param("error") String error);
}
