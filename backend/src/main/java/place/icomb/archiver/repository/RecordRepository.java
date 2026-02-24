package place.icomb.archiver.repository;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import place.icomb.archiver.model.Record;

@Repository
public interface RecordRepository
    extends CrudRepository<Record, Long>, PagingAndSortingRepository<Record, Long> {

  @Query(
      "SELECT * FROM record WHERE source_system = :sourceSystem AND source_record_id = :sourceRecordId")
  Optional<Record> findBySourceSystemAndSourceRecordId(
      @Param("sourceSystem") String sourceSystem, @Param("sourceRecordId") String sourceRecordId);

  @Query("SELECT * FROM record WHERE source_system = :sourceSystem")
  java.util.List<Record> findBySourceSystem(@Param("sourceSystem") String sourceSystem);
}
