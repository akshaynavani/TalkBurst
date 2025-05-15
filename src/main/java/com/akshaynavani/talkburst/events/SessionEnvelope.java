package com.akshaynavani.talkburst.events;

import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.floor.FloorEvent;
import com.fasterxml.jackson.annotation.JsonInclude;

/** JSON payload on a session's control channel. Exactly one of the event fields is set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEnvelope(
        Kind kind,
        String sessionId,
        String origin,
        FloorEvent floor,
        ParticipantChange participant) {

    public enum Kind { FLOOR, PARTICIPANT }

    public record ParticipantChange(Change change, Participant participant) {
        public enum Change { JOINED, LEFT }
    }
}
