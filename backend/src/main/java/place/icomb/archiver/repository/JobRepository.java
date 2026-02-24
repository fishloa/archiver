package place.icomb.archiver.repository;

import place.icomb.archiver.model.Job;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

  List<Job> findByRecordId(Long recordId);

  @Query("SELECT * FROM job WHERE record_id = :recordId AND kind = :kind AND status = :status")
  List<Job> findByRecordIdAndKindAndStatus(
      @Param("recordId") Long recordId,
      @Param("kind") String kind,
      @Param("status") String status);

  @Modifying
  @Query("UPDATE job SET status = :status, finished_at = now() WHERE id = :id")
  void updateStatus(@Param("id") Long id, @Param("status") String status);

  @Modifying
  @Query("UPDATE job SET status = 'failed', error = :error, finished_at = now() WHERE id = :id")
  void markFailed(@Param("id") Long id, @Param("error") String error);
}
