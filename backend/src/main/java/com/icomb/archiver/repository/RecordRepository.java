package com.icomb.archiver.repository;

import com.icomb.archiver.model.Record;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RecordRepository
    extends CrudRepository<Record, Long>, PagingAndSortingRepository<Record, Long> {

  @Query("SELECT * FROM record WHERE source_system = :sourceSystem AND source_record_id = :sourceRecordId")
  Optional<Record> findBySourceSystemAndSourceRecordId(
      @Param("sourceSystem") String sourceSystem,
      @Param("sourceRecordId") String sourceRecordId);
}
