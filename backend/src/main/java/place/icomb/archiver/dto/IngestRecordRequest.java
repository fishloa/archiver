package place.icomb.archiver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IngestRecordRequest(
    @NotNull Long archiveId,
    Long collectionId,
    @NotBlank String sourceSystem,
    @NotBlank String sourceRecordId,
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
    String rawSourceMetadata,
    String lang) {}
