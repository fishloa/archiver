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
    java.util.List<String> indexTerms,
    String rawSourceMetadata,
    String lang,
    String metadataLang,
    String sourceUrl,
    /**
     * Which model translates this record's pages: "bulk" (default) or "best".
     *
     * <p>Chosen here so the pipeline enqueues one job per page of one kind. Queuing an upgrade
     * after a bulk pass meant translating the record twice, with the two racing each other.
     */
    String translationQuality) {}
