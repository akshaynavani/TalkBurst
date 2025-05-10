package com.akshaynavani.talkburst.api.dto;

import java.time.Instant;
import java.util.List;

import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.TalkSession;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(
        String id,
        String name,
        String createdBy,
        Instant createdAt,
        int maxParticipants,
        String websocketPath,
        List<Participant> participants) {

    public static SessionResponse of(TalkSession s, List<Participant> participants) {
        return new SessionResponse(s.id(), s.name(), s.createdBy(), s.createdAt(),
                s.maxParticipants(), "/ws/ptt?session=" + s.id(), participants);
    }
}
