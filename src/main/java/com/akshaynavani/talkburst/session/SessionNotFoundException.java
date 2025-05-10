package com.akshaynavani.talkburst.session;

public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String sessionId) {
        super("session not found: " + sessionId);
    }
}
