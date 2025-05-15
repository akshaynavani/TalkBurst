package com.akshaynavani.talkburst.api.dto;

import java.time.Instant;
import java.util.List;

import com.akshaynavani.talkburst.domain.FloorState;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Public view of the floor. Deliberately omits the holder's grant token. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FloorStateResponse(String sessionId, Holder holder, List<String> queue) {

    public record Holder(String userId, String role, Instant grantedAt, Instant expiresAt) {
    }

    public static FloorStateResponse of(FloorState s) {
        Holder h = s.holder() == null ? null : new Holder(s.holder().userId(), s.holder().role().name(),
                s.holder().grantedAt(), s.holder().expiresAt());
        return new FloorStateResponse(s.sessionId(), h, s.queue());
    }
}
