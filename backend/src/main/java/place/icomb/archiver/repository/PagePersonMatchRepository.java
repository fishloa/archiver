package place.icomb.archiver.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.PagePersonMatch;

public interface PagePersonMatchRepository extends CrudRepository<PagePersonMatch, Long> {

  List<PagePersonMatch> findByPageId(Long pageId);

  @Query("SELECT * FROM page_person_match WHERE page_id IN (:pageIds)")
  List<PagePersonMatch> findByPageIdIn(@Param("pageIds") Collection<Long> pageIds);

  @Modifying
  @Query("DELETE FROM page_person_match WHERE page_id = :pageId")
  void deleteByPageId(@Param("pageId") Long pageId);

  boolean existsByPageId(Long pageId);

  @Modifying
  @Query("TRUNCATE page_person_match")
  void truncateAll();
}
