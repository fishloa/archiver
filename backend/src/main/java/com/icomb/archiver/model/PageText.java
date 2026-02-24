package com.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

@Table("page_text")
public class PageText {

  @Id private Long id;
  private Long pageId;
  private String engine;
  private Float confidence;
  private String textRaw;
  @ReadOnlyProperty private String textNorm;
  private String hocr;
  private Instant createdAt;

  public PageText() {}

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

  public String getEngine() {
    return engine;
  }

  public void setEngine(String engine) {
    this.engine = engine;
  }

  public Float getConfidence() {
    return confidence;
  }

  public void setConfidence(Float confidence) {
    this.confidence = confidence;
  }

  public String getTextRaw() {
    return textRaw;
  }

  public void setTextRaw(String textRaw) {
    this.textRaw = textRaw;
  }

  public String getTextNorm() {
    return textNorm;
  }

  public String getHocr() {
    return hocr;
  }

  public void setHocr(String hocr) {
    this.hocr = hocr;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
