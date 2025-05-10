package com.akshaynavani.talkburst.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 64) String createdBy,
        @Min(2) @Max(1024) Integer maxParticipants) {
}
