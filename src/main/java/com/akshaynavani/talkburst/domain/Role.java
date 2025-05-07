package com.akshaynavani.talkburst.domain;

/**
 * Participant roles, modelled on dispatch-style PTT systems.
 * Lower {@link #priority()} value means higher precedence for the floor.
 */
public enum Role {
    /** Control-room operator: highest priority and may pre-empt any talker. */
    DISPATCHER(0, true, true),
    /** Team lead: jumps ahead of members in the queue but cannot pre-empt. */
    SUPERVISOR(1, true, false),
    /** Regular participant. */
    MEMBER(2, true, false),
    /** Receive-only: can hear the group but never request the floor. */
    LISTENER(3, false, false);

    private final int priority;
    private final boolean canTalk;
    private final boolean canPreempt;

    Role(int priority, boolean canTalk, boolean canPreempt) {
        this.priority = priority;
        this.canTalk = canTalk;
        this.canPreempt = canPreempt;
    }

    public int priority() {
        return priority;
    }

    public boolean canTalk() {
        return canTalk;
    }

    public boolean canPreempt() {
        return canPreempt;
    }

    /** True if this role strictly outranks {@code other}. */
    public boolean outranks(Role other) {
        return this.priority < other.priority;
    }

    public static Role parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMBER;
        }
        return Role.valueOf(raw.trim().toUpperCase());
    }
}
