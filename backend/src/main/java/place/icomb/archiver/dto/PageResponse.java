package place.icomb.archiver.dto;

public record PageResponse(
    Long id,
    Long recordId,
    int seq,
    Long attachmentId,
    String pageLabel,
    Integer width,
    Integer height,
    String sourceUrl) {}
