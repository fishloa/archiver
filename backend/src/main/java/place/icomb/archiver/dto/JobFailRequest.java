package place.icomb.archiver.dto;

import jakarta.validation.constraints.NotBlank;

public record JobFailRequest(@NotBlank String error) {}
