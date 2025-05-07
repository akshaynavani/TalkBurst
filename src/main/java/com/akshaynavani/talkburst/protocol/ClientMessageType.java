package com.akshaynavani.talkburst.protocol;

/** Control messages a client may send as JSON text frames. */
public enum ClientMessageType {
    /** Ask for the floor (press PTT). */
    FLOOR_REQUEST,
    /** Give up the floor (release PTT). */
    FLOOR_RELEASE,
    /** Extend the current grant while still talking. */
    FLOOR_RENEW,
    /** Withdraw a queued request without ever getting the floor. */
    FLOOR_CANCEL,
    /** Ask for a snapshot of the floor and participants. */
    STATE,
    /** Application-level keepalive. */
    PING
}
