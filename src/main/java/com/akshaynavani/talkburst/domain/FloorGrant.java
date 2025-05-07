package com.akshaynavani.talkburst.domain;

import java.time.Instant;

/**
 * The current holder of a session's floor.
 *
 * @param token     opaque per-grant token; release/renew must present it
 * @param grantedAt when the burst started (used to enforce the max burst length)
 * @param expiresAt when the grant lapses unless renewed
 */
public record FloorGrant(
        String sessionId,
        String userId,
        Role role,
        String token,
        Instant grantedAt,
        Instant expiresAt) {
}
