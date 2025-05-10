package com.akshaynavani.talkburst.session;

public class SessionFullException extends RuntimeException {
    public SessionFullException(String sessionId) {
        super("session is full: " + sessionId);
    }
}
