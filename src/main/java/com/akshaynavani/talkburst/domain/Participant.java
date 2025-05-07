package com.akshaynavani.talkburst.domain;

import java.util.Objects;

public record Participant(String userId, String displayName, Role role) {

    public Participant {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if (displayName == null || displayName.isBlank()) {
            displayName = userId;
        }
    }
}
