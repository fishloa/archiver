package com.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("entity_hit")
public class EntityHit {

  @Id private Long id;
  private Long pageId;
  private String entityType;
  private String value;
  private Float confidence;
  private Integer startOffset;
  private Integer endOffset;
  private Instant createdAt;

  public EntityHit() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getPageId() {
    return pageId;
  }

  public void setPageId(Long pageId) {
    this.pageId = pageId;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public Float getConfidence() {
    return confidence;
  }

  public void setConfidence(Float confidence) {
    this.confidence = confidence;
  }

  public Integer getStartOffset() {
    return startOffset;
  }

  public void setStartOffset(Integer startOffset) {
    this.startOffset = startOffset;
  }

  public Integer getEndOffset() {
    return endOffset;
  }

  public void setEndOffset(Integer endOffset) {
    this.endOffset = endOffset;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
