package com.akshaynavani.talkburst.domain;

import java.util.List;

/** Snapshot of a session's floor: who holds it and who is waiting, in order. */
public record FloorState(String sessionId, FloorGrant holder, List<String> queue) {

    public boolean isIdle() {
        return holder == null;
    }
}
