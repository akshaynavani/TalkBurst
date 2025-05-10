package com.akshaynavani.talkburst.api.dto;

import java.time.Instant;

public record ApiError(int status, String code, String message, Instant ts) {
    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, Instant.now());
    }
}
