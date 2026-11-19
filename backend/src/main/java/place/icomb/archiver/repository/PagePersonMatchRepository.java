package place.icomb.archiver.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.PagePersonMatch;

public interface PagePersonMatchRepository extends CrudRepository<PagePersonMatch, Long> {

  @Modifying
  @Query("DELETE FROM page_person_match WHERE page_id = :pageId")
  void deleteByPageId(@Param("pageId") Long pageId);
}
