package com.icomb.archiver.dto;

public record IngestRecordResponse(Long id, String sourceSystem, String sourceRecordId, String status) {}
