package com.icomb.archiver.dto;

import jakarta.validation.constraints.NotBlank;

public record JobClaimRequest(@NotBlank String kind) {}
