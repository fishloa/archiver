package com.icomb.archiver.dto;

import java.time.Instant;

public record RecordResponse(
    Long id,
    Long archiveId,
    Long collectionId,
    String sourceSystem,
    String sourceRecordId,
    String title,
    String description,
    String dateRangeText,
    Integer dateStartYear,
    Integer dateEndYear,
    String referenceCode,
    String inventoryNumber,
    String callNumber,
    String containerType,
    String containerNumber,
    String findingAidNumber,
    String indexTerms,
    Long pdfAttachmentId,
    int attachmentCount,
    int pageCount,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
