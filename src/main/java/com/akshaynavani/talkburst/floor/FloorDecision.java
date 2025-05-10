package com.akshaynavani.talkburst.floor;

import com.akshaynavani.talkburst.domain.FloorGrant;

/** Direct answer to the participant who asked for the floor. */
public record FloorDecision(Outcome outcome, FloorGrant grant, Integer queuePosition, String reason) {

    public enum Outcome { GRANTED, ALREADY_HOLDING, QUEUED, QUEUE_FULL, DENIED }

    public static FloorDecision granted(FloorGrant g) {
        return new FloorDecision(Outcome.GRANTED, g, null, null);
    }

    public static FloorDecision holding(FloorGrant g) {
        return new FloorDecision(Outcome.ALREADY_HOLDING, g, null, null);
    }

    public static FloorDecision queued(int position) {
        return new FloorDecision(Outcome.QUEUED, null, position, null);
    }

    public static FloorDecision queueFull() {
        return new FloorDecision(Outcome.QUEUE_FULL, null, null, "floor queue is full");
    }

    public static FloorDecision denied(String reason) {
        return new FloorDecision(Outcome.DENIED, null, null, reason);
    }
}
