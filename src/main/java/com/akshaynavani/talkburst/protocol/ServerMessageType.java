package com.akshaynavani.talkburst.protocol;

/** Messages the server pushes to clients as JSON text frames. */
public enum ServerMessageType {
    WELCOME,
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,
    FLOOR_GRANTED,
    FLOOR_QUEUED,
    FLOOR_DENIED,
    FLOOR_RELEASED,
    FLOOR_RENEWED,
    FLOOR_REVOKED,
    FLOOR_EXPIRED,
    FLOOR_QUEUE_UPDATED,
    FLOOR_STATE,
    PONG,
    ERROR
}
