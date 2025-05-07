package com.akshaynavani.talkburst.domain;

import java.time.Instant;

/** Metadata for a push-to-talk group session ("talk group"). */
public record TalkSession(
        String id,
        String name,
        String createdBy,
        Instant createdAt,
        int maxParticipants) {
}
