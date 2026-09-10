package place.icomb.archiver.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import place.icomb.archiver.model.RecordTranslation;

/** Record metadata translations, kept per record per model. */
public interface RecordTranslationRepository extends Repository<RecordTranslation, Long> {

  /**
   * Records a translation, replacing any earlier attempt by the same model.
   *
   * <p>Re-running one model overwrites only its own row, so a better translation never destroys the
   * one it improves on — and an upgrade already done is visible rather than paid for twice.
   */
  @Modifying
  @Query(
      """
      INSERT INTO record_translation (record_id, model, title_en, description_en, created_at)
      VALUES (:recordId, :model, :titleEn, :descriptionEn, now())
      ON CONFLICT (record_id, model) DO UPDATE
        SET title_en = EXCLUDED.title_en,
            description_en = EXCLUDED.description_en,
            created_at = now()
      """)
  void upsert(
      @Param("recordId") Long recordId,
      @Param("model") String model,
      @Param("titleEn") String titleEn,
      @Param("descriptionEn") String descriptionEn);
}
