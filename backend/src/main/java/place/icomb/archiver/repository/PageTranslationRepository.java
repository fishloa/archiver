package place.icomb.archiver.repository;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.PageTranslation;

/** Translations kept per page per model. */
public interface PageTranslationRepository extends Repository<PageTranslation, Long> {

  /**
   * Records a translation, replacing any earlier attempt by the same model.
   *
   * <p>Re-running one model overwrites only its own row, so an upgrade never destroys the cheaper
   * translation it improves on.
   */
  @Modifying
  @Query(
      """
      INSERT INTO page_translation (page_id, model, text_en, created_at)
      VALUES (:pageId, :model, :textEn, now())
      ON CONFLICT (page_id, model) DO UPDATE
        SET text_en = EXCLUDED.text_en, created_at = now()
      """)
  void upsert(
      @Param("pageId") Long pageId, @Param("model") String model, @Param("textEn") String textEn);

  /** Models that have already translated this page. */
  @Query("SELECT model FROM page_translation WHERE page_id = :pageId")
  List<String> modelsFor(@Param("pageId") Long pageId);

  /**
   * Every translation held for a page.
   *
   * <p>Returns the entity rather than a column projection: Spring Data JDBC maps a {@code
   * List<Map<String,Object>>} as a single-column result and fails with "expected 1, actual 3" the
   * moment a page actually has a translation.
   */
  @Query("SELECT * FROM page_translation WHERE page_id = :pageId ORDER BY model")
  List<PageTranslation> findByPageId(@Param("pageId") Long pageId);

  /** Pages of a record that no model has translated at the given quality yet. */
  @Query(
      """
      SELECT p.id FROM page p
      WHERE p.record_id = :recordId
        AND EXISTS (SELECT 1 FROM page_text pt WHERE pt.page_id = p.id
                      AND pt.text_raw IS NOT NULL AND pt.text_raw <> '')
        AND NOT EXISTS (SELECT 1 FROM page_translation tr
                          WHERE tr.page_id = p.id AND tr.model = :model)
      ORDER BY p.seq
      """)
  List<Long> pagesMissingModel(@Param("recordId") Long recordId, @Param("model") String model);

  /** How many pages of a record already have a translation from this model. */
  @Query(
      """
      SELECT count(*) FROM page p
      JOIN page_translation tr ON tr.page_id = p.id AND tr.model = :model
      WHERE p.record_id = :recordId
      """)
  Integer countWithModel(@Param("recordId") Long recordId, @Param("model") String model);
}
