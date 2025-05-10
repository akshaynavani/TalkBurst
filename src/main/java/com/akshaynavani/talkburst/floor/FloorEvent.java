package com.akshaynavani.talkburst.floor;

import java.util.List;

import com.akshaynavani.talkburst.domain.FloorGrant;

/**
 * A change to a session's floor that every participant should learn about.
 * Grants carry the holder's token; transport layers must only reveal it to the holder.
 */
public record FloorEvent(Type type, String sessionId, FloorGrant grant, String reason, List<String> queue) {

    public enum Type { GRANTED, RELEASED, REVOKED, EXPIRED, QUEUE_UPDATED }

    public static FloorEvent of(Type type, FloorGrant grant, String reason) {
        return new FloorEvent(type, grant.sessionId(), grant, reason, null);
    }

    public static FloorEvent queue(String sessionId, List<String> queue) {
        return new FloorEvent(Type.QUEUE_UPDATED, sessionId, null, null, queue);
    }
}
