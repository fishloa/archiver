package place.icomb.archiver.dto;

import java.time.Instant;

public record JobResponse(
    Long id,
    String kind,
    Long recordId,
    Long pageId,
    String payload,
    String status,
    int attempts,
    String error,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
