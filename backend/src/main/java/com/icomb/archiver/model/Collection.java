package com.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("collection")
public class Collection {

  @Id private Long id;
  private Long archiveId;
  private String name;
  private String code;
  private String rawSourceMetadata;
  private Instant createdAt;

  public Collection() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getArchiveId() {
    return archiveId;
  }

  public void setArchiveId(Long archiveId) {
    this.archiveId = archiveId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getRawSourceMetadata() {
    return rawSourceMetadata;
  }

  public void setRawSourceMetadata(String rawSourceMetadata) {
    this.rawSourceMetadata = rawSourceMetadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
