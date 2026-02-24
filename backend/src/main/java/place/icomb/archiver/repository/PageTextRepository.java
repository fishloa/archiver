package place.icomb.archiver.repository;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.PageText;

public interface PageTextRepository extends CrudRepository<PageText, Long> {

  List<PageText> findByPageId(Long pageId);

  @Query(
      """
      SELECT pt.* FROM page_text pt
      WHERE pt.text_norm ILIKE '%' || immutable_unaccent(lower(:term)) || '%'
      ORDER BY pt.confidence DESC NULLS LAST
      LIMIT :limit OFFSET :offset
      """)
  List<PageText> searchByText(
      @Param("term") String term, @Param("limit") int limit, @Param("offset") int offset);

  @Query(
      """
      SELECT count(*) FROM page_text pt
      WHERE pt.text_norm ILIKE '%' || immutable_unaccent(lower(:term)) || '%'
      """)
  long countByText(@Param("term") String term);
}
