package com.akshaynavani.talkburst.session;

/** Central place for every Redis key and channel name used by TalkBurst. */
public final class RedisKeys {

    public static final String PREFIX = "tb:";
    public static final String SESSION_INDEX = PREFIX + "sessions";
    /** Sessions whose floor grant needs checking by the expiry sweeper. */
    public static final String ACTIVE_FLOORS = PREFIX + "floors:active";

    private RedisKeys() {
    }

    public static String session(String sessionId) {
        return PREFIX + "session:" + sessionId;
    }

    public static String members(String sessionId) {
        return PREFIX + "members:" + sessionId;
    }

    public static String floor(String sessionId) {
        return PREFIX + "floor:" + sessionId;
    }

    public static String queue(String sessionId) {
        return PREFIX + "queue:" + sessionId;
    }

    public static String events(String sessionId) {
        return PREFIX + "events:" + sessionId;
    }

    public static String audio(String sessionId) {
        return PREFIX + "audio:" + sessionId;
    }

    public static String sessionIdFromChannel(String channel) {
        return channel.substring(channel.indexOf(':', PREFIX.length()) + 1);
    }
}
