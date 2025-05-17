package com.akshaynavani.talkburst.ws;

import java.io.IOException;
import java.time.Instant;

import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.Participant;

/**
 * A participant's live socket on this node, plus a local cache of whether they hold
 * the floor so audio frames can be authorised without a Redis round-trip per frame.
 */
public class PttConnection {

    private final WebSocketSession socket;
    private final String sessionId;
    private final Participant participant;
    private volatile FloorGrant heldGrant;
    private volatile long lastAudioRejectMs;

    public PttConnection(WebSocketSession socket, String sessionId, Participant participant) {
        this.socket = socket;
        this.sessionId = sessionId;
        this.participant = participant;
    }

    public String id() {
        return socket.getId();
    }

    public String sessionId() {
        return sessionId;
    }

    public Participant participant() {
        return participant;
    }

    public String userId() {
        return participant.userId();
    }

    public WebSocketSession socket() {
        return socket;
    }

    public FloorGrant heldGrant() {
        return heldGrant;
    }

    public void setHeldGrant(FloorGrant grant) {
        this.heldGrant = grant;
    }

    /** Clears the cached grant only if it is the one being ended (tokens are unique per grant). */
    public void clearGrant(String token) {
        FloorGrant g = heldGrant;
        if (g != null && g.token().equals(token)) {
            heldGrant = null;
        }
    }

    public boolean holdsFloor(Instant now) {
        FloorGrant g = heldGrant;
        return g != null && g.expiresAt().isAfter(now);
    }

    /** Rate-limits "you don't hold the floor" errors for stray audio to one per second. */
    public boolean shouldNotifyAudioReject(long nowMs) {
        if (nowMs - lastAudioRejectMs < 1_000) {
            return false;
        }
        lastAudioRejectMs = nowMs;
        return true;
    }

    public boolean isOpen() {
        return socket.isOpen();
    }

    public void send(WebSocketMessage<?> message) throws IOException {
        socket.sendMessage(message);
    }
}
