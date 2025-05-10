package com.akshaynavani.talkburst.floor;

import java.time.Instant;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.Role;

/**
 * Encodes the compact pipe-delimited strings that the Lua scripts store in Redis.
 *
 * <pre>
 * queue member: userId|ROLE|priority
 * floor value:  userId|ROLE|priority|token|grantedAtMs|expiresAtMs
 * </pre>
 */
final class FloorValueCodec {

    /** Priority bands are spaced so enqueue time only breaks ties within a band. */
    static final double PRIORITY_BAND = 1e13;

    private FloorValueCodec() {
    }

    static String member(Participant p) {
        return p.userId() + "|" + p.role().name() + "|" + p.role().priority();
    }

    static double queueScore(Role role, long nowMillis) {
        return role.priority() * PRIORITY_BAND + nowMillis;
    }

    static String userIdOfMember(String member) {
        return member.substring(0, member.indexOf('|'));
    }

    /** Returns null for null/empty values, which the scripts use for "nothing". */
    static FloorGrant grant(String sessionId, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] f = value.split("\\|");
        if (f.length != 6) {
            throw new IllegalStateException("corrupt floor value: " + value);
        }
        return new FloorGrant(sessionId, f[0], Role.valueOf(f[1]), f[3],
                Instant.ofEpochMilli(Long.parseLong(f[4])),
                Instant.ofEpochMilli(Long.parseLong(f[5])));
    }
}
