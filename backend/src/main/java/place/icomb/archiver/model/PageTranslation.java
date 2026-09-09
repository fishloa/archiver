package place.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One model's translation of one page.
 *
 * <p>Translations accumulate rather than overwrite: page_text.text_en caches whichever is
 * preferred, while this records what each model actually produced. That makes "has this page
 * already had the better translation?" answerable, which is what stops an upgrade being paid for
 * twice.
 */
@Table("page_translation")
public class PageTranslation {

  @Id private Long id;
  private Long pageId;
  private String model;
  private String textEn;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getPageId() {
    return pageId;
  }

  public String getModel() {
    return model;
  }

  public String getTextEn() {
    return textEn;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
