package place.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("evidence")
public class Evidence {

  @Id private Long id;
  private Long entityHitId;
  private Long pageId;
  private String snippet;
  private String boundingBox;
  private Instant createdAt;

  public Evidence() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getEntityHitId() {
    return entityHitId;
  }

  public void setEntityHitId(Long entityHitId) {
    this.entityHitId = entityHitId;
  }

  public Long getPageId() {
    return pageId;
  }

  public void setPageId(Long pageId) {
    this.pageId = pageId;
  }

  public String getSnippet() {
    return snippet;
  }

  public void setSnippet(String snippet) {
    this.snippet = snippet;
  }

  public String getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(String boundingBox) {
    this.boundingBox = boundingBox;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
