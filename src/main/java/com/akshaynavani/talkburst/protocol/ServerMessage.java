package com.akshaynavani.talkburst.protocol;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outbound message. {@code data} carries type-specific fields so the envelope
 * stays stable as the protocol grows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerMessage(
        ServerMessageType type,
        String sessionId,
        String userId,
        String requestId,
        Map<String, Object> data,
        Instant ts) {

    public static ServerMessage of(ServerMessageType type, String sessionId, String userId,
                                   Map<String, Object> data) {
        return new ServerMessage(type, sessionId, userId, null, data, Instant.now());
    }

    public ServerMessage withRequestId(String id) {
        return new ServerMessage(type, sessionId, userId, id, data, ts);
    }

    public static ServerMessage error(String sessionId, String requestId, String code, String message) {
        return new ServerMessage(ServerMessageType.ERROR, sessionId, null, requestId,
                Map.of("code", code, "message", message), Instant.now());
    }
}
