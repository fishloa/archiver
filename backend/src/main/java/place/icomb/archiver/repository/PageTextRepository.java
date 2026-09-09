package place.icomb.archiver.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.PageText;

public interface PageTextRepository extends CrudRepository<PageText, Long> {

  List<PageText> findByPageId(Long pageId);

  /**
   * The page's current transcription, or empty if it has never been OCR'd.
   *
   * <p>page_text carries a UNIQUE constraint on page_id (V26), so a page has at most one row.
   * Callers must not rank rows by confidence to pick a winner: confidence is not comparable across
   * engines — PaddleOCR emitted a per-character CTC score while the vision models emit nothing —
   * and ranking by it served the worst available transcription for 65,221 pages.
   */
  @Query("SELECT * FROM page_text WHERE page_id = :pageId")
  Optional<PageText> findCurrentByPageId(@Param("pageId") Long pageId);

  /**
   * Removes the page's existing transcription so a new one can take its place.
   *
   * <p>Required before inserting: the UNIQUE constraint makes a second INSERT fail rather than
   * silently accumulate a second engine's output, which is how the archive ended up with two
   * transcriptions on 65,221 pages. The V26 trigger copies the outgoing row to page_ocr_history
   * first, so replacing a transcription never loses the record that its engine ran.
   */
  @Modifying
  @Query("DELETE FROM page_text WHERE page_id = :pageId")
  void deleteByPageId(@Param("pageId") Long pageId);

  /**
   * Stores an OCR result, replacing whatever the page had before.
   *
   * <p>The provider's full response is kept alongside the text: it carries per-block bounding boxes
   * and dimensions that the searchable PDF needs to place its invisible text layer where the words
   * actually are. CAST rather than the {@code ::jsonb} shorthand, which collides with
   * named-parameter parsing.
   */
  @Modifying
  @Query(
      """
      INSERT INTO page_text (page_id, engine, text_raw, content_type, created_at, raw_response)
      VALUES (:pageId, :engine, :textRaw, :contentType, now(), CAST(:rawResponse AS jsonb))
      """)
  void insertOcrResult(
      @Param("pageId") Long pageId,
      @Param("engine") String engine,
      @Param("textRaw") String textRaw,
      @Param("contentType") String contentType,
      @Param("rawResponse") String rawResponse);

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
