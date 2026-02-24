package place.icomb.archiver.repository;

import place.icomb.archiver.model.Page;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PageRepository extends CrudRepository<Page, Long> {

  List<Page> findByRecordId(Long recordId);

  @Query("SELECT * FROM page WHERE record_id = :recordId AND seq = :seq")
  Optional<Page> findByRecordIdAndSeq(@Param("recordId") Long recordId, @Param("seq") int seq);

  @Query("SELECT COUNT(*) FROM page WHERE record_id = :recordId")
  int countByRecordId(@Param("recordId") Long recordId);
}
