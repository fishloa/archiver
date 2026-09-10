package place.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One model's translation of one record's catalogue metadata.
 *
 * <p>The page-level counterpart of {@link PageTranslation}, and for the same reason: the title was
 * previously written straight over record.title_en with no note of what produced it, so a bad
 * translation was indistinguishable from a good one and could not be superseded.
 */
@Table("record_translation")
public class RecordTranslation {

  @Id private Long id;
  private Long recordId;
  private String model;
  private String titleEn;
  private String descriptionEn;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getRecordId() {
    return recordId;
  }

  public String getModel() {
    return model;
  }

  public String getTitleEn() {
    return titleEn;
  }

  public String getDescriptionEn() {
    return descriptionEn;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
