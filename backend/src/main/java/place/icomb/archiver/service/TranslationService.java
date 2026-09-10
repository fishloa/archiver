package place.icomb.archiver.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.repository.PageTranslationRepository;

/**
 * The one place that decides which of a page's translations is the right one to show.
 *
 * <p>Every model's output is kept — a cheap translation is never destroyed by a better one, and an
 * upgrade already paid for can be recognised rather than bought twice — so something has to choose
 * between them. That choice was previously made in five places: the batch stage inferred it by
 * comparing text bytes, the HTTP worker endpoint ignored it and overwrote whatever was there, and
 * the viewer, the machine API and the person matcher each read the cached column and trusted it.
 * The result was 53 pages displaying mistral-small while the mistral-medium upgrade sat unused in
 * page_translation.
 *
 * <p>{@code page_text.text_en} remains as a cache, because search and the pipeline read it in bulk,
 * but nothing infers the ranking from it any more: it is written from here and only from here.
 */
@Service
public class TranslationService {

  private final JdbcTemplate jdbc;
  private final PageTranslationRepository translations;

  public TranslationService(JdbcTemplate jdbc, PageTranslationRepository translations) {
    this.jdbc = jdbc;
    this.translations = translations;
  }

  // -------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------

  /** The best translation held for a page, or null if it has none. */
  public String bestEnglish(Long pageId) {
    if (pageId == null) return null;
    return jdbc.query(
        """
        SELECT tr.text_en FROM page_translation tr
        WHERE tr.page_id = ? AND tr.text_en IS NOT NULL AND tr.text_en <> ''
        ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
        LIMIT 1
        """,
        rs -> rs.next() ? rs.getString(1) : null,
        pageId,
        TranslationModels.ranksLiteral());
  }

  /**
   * Best translation for every page of a record, keyed by page sequence.
   *
   * <p>One query for the record rather than one per page: an export of a 942-page record would
   * otherwise make 942 round trips to answer the same question.
   */
  public Map<Integer, String> bestEnglishByRecord(Long recordId) {
    Map<Integer, String> out = new HashMap<>();
    jdbc.query(
        """
        SELECT p.seq, best.text_en
        FROM page p
        JOIN LATERAL (
            SELECT tr.text_en FROM page_translation tr
            WHERE tr.page_id = p.id AND tr.text_en IS NOT NULL AND tr.text_en <> ''
            ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
            LIMIT 1
        ) best ON true
        WHERE p.record_id = ?
        """,
        rs -> {
          out.put(rs.getInt(1), rs.getString(2));
        },
        TranslationModels.ranksLiteral(),
        recordId);
    return out;
  }

  /** The model behind the translation a page currently shows, or null. */
  public String bestModel(Long pageId) {
    if (pageId == null) return null;
    return jdbc.query(
        """
        SELECT tr.model FROM page_translation tr
        WHERE tr.page_id = ? AND tr.text_en IS NOT NULL AND tr.text_en <> ''
        ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
        LIMIT 1
        """,
        rs -> rs.next() ? rs.getString(1) : null,
        pageId,
        TranslationModels.ranksLiteral());
  }

  /**
   * Which models actually back a record's shown translations, best first, with page counts.
   *
   * <p>A record can be partly upgraded. The cover sheet names what is really there rather than the
   * best model present, so "upgraded" is never claimed for pages that were not.
   */
  public List<String[]> modelBreakdown(Long recordId) {
    List<String[]> out = new ArrayList<>();
    jdbc.query(
        """
        SELECT shown.model, count(*) AS pages
        FROM page p
        JOIN LATERAL (
            SELECT tr.model FROM page_translation tr
            WHERE tr.page_id = p.id AND tr.text_en IS NOT NULL AND tr.text_en <> ''
            ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
            LIMIT 1
        ) shown ON true
        WHERE p.record_id = ?
        GROUP BY shown.model
        ORDER BY COALESCE(array_position(?::text[], shown.model), 999)
        """,
        rs -> {
          out.add(new String[] {rs.getString(1), String.valueOf(rs.getInt(2))});
        },
        TranslationModels.ranksLiteral(),
        recordId,
        TranslationModels.ranksLiteral());
    return out;
  }

  // -------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------

  /** Records a model's translation and repoints the cache at whichever is now best. */
  public void record(Long pageId, String model, String textEn) {
    if (textEn != null && !textEn.isBlank()) {
      translations.upsert(pageId, model, textEn);
    }
    refreshShown(pageId);
  }

  /**
   * Points {@code page_text.text_en} at the page's best translation.
   *
   * <p>Recomputed, never inferred. The version this replaced worked out which model was on show by
   * joining on text equality, which reads a model's identity out of its bytes: any edit to the
   * cached text broke the match and the page silently kept what it had. Recomputing also makes the
   * cache self-healing — a page that has drifted is corrected the next time anything touches it.
   */
  public void refreshShown(Long pageId) {
    jdbc.update(
        """
        UPDATE page_text SET text_en = (
            SELECT tr.text_en FROM page_translation tr
            WHERE tr.page_id = ? AND tr.text_en IS NOT NULL AND tr.text_en <> ''
            ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
            LIMIT 1
        )
        WHERE page_id = ?
          AND EXISTS (SELECT 1 FROM page_translation tr WHERE tr.page_id = ?)
        """,
        pageId,
        TranslationModels.ranksLiteral(),
        pageId,
        pageId);
  }

  /**
   * Repairs every page whose cache has fallen behind its best translation.
   *
   * @return how many pages were corrected
   */
  public int refreshAllStale() {
    return jdbc.update(
        """
        UPDATE page_text pt SET text_en = best.text_en
        FROM (
            SELECT p.id AS page_id, (
                SELECT tr.text_en FROM page_translation tr
                WHERE tr.page_id = p.id AND tr.text_en IS NOT NULL AND tr.text_en <> ''
                ORDER BY COALESCE(array_position(?::text[], tr.model), 999)
                LIMIT 1
            ) AS text_en
            FROM page p
        ) best
        WHERE pt.page_id = best.page_id
          AND best.text_en IS NOT NULL
          AND pt.text_en IS DISTINCT FROM best.text_en
        """,
        TranslationModels.ranksLiteral());
  }
}
