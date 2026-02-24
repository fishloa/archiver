package com.icomb.archiver.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("record")
public class Record {

  @Id private Long id;
  private Long archiveId;
  private Long collectionId;
  private String sourceSystem;
  private String sourceRecordId;
  private String title;
  private String description;
  private String dateRangeText;
  private Integer dateStartYear;
  private Integer dateEndYear;
  private String referenceCode;
  private String inventoryNumber;
  private String callNumber;
  private String containerType;
  private String containerNumber;
  private String findingAidNumber;
  private String indexTerms;
  private String rawSourceMetadata;
  private Long pdfAttachmentId;
  private int attachmentCount;
  private int pageCount;
  private String status;
  private Instant createdAt;
  private Instant updatedAt;

  public Record() {}

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

  public Long getCollectionId() {
    return collectionId;
  }

  public void setCollectionId(Long collectionId) {
    this.collectionId = collectionId;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public void setSourceSystem(String sourceSystem) {
    this.sourceSystem = sourceSystem;
  }

  public String getSourceRecordId() {
    return sourceRecordId;
  }

  public void setSourceRecordId(String sourceRecordId) {
    this.sourceRecordId = sourceRecordId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getDateRangeText() {
    return dateRangeText;
  }

  public void setDateRangeText(String dateRangeText) {
    this.dateRangeText = dateRangeText;
  }

  public Integer getDateStartYear() {
    return dateStartYear;
  }

  public void setDateStartYear(Integer dateStartYear) {
    this.dateStartYear = dateStartYear;
  }

  public Integer getDateEndYear() {
    return dateEndYear;
  }

  public void setDateEndYear(Integer dateEndYear) {
    this.dateEndYear = dateEndYear;
  }

  public String getReferenceCode() {
    return referenceCode;
  }

  public void setReferenceCode(String referenceCode) {
    this.referenceCode = referenceCode;
  }

  public String getInventoryNumber() {
    return inventoryNumber;
  }

  public void setInventoryNumber(String inventoryNumber) {
    this.inventoryNumber = inventoryNumber;
  }

  public String getCallNumber() {
    return callNumber;
  }

  public void setCallNumber(String callNumber) {
    this.callNumber = callNumber;
  }

  public String getContainerType() {
    return containerType;
  }

  public void setContainerType(String containerType) {
    this.containerType = containerType;
  }

  public String getContainerNumber() {
    return containerNumber;
  }

  public void setContainerNumber(String containerNumber) {
    this.containerNumber = containerNumber;
  }

  public String getFindingAidNumber() {
    return findingAidNumber;
  }

  public void setFindingAidNumber(String findingAidNumber) {
    this.findingAidNumber = findingAidNumber;
  }

  public String getIndexTerms() {
    return indexTerms;
  }

  public void setIndexTerms(String indexTerms) {
    this.indexTerms = indexTerms;
  }

  public String getRawSourceMetadata() {
    return rawSourceMetadata;
  }

  public void setRawSourceMetadata(String rawSourceMetadata) {
    this.rawSourceMetadata = rawSourceMetadata;
  }

  public Long getPdfAttachmentId() {
    return pdfAttachmentId;
  }

  public void setPdfAttachmentId(Long pdfAttachmentId) {
    this.pdfAttachmentId = pdfAttachmentId;
  }

  public int getAttachmentCount() {
    return attachmentCount;
  }

  public void setAttachmentCount(int attachmentCount) {
    this.attachmentCount = attachmentCount;
  }

  public int getPageCount() {
    return pageCount;
  }

  public void setPageCount(int pageCount) {
    this.pageCount = pageCount;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
