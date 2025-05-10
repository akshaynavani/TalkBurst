package com.akshaynavani.talkburst.floor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically expires lapsed talk bursts and hands the floor to the next waiter.
 * Every node runs this; the sweep script is atomic so each expiry is handled once.
 */
@Component
public class FloorExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(FloorExpiryScheduler.class);

    private final RedisFloorStore store;
    private final FloorControlService floor;

    public FloorExpiryScheduler(RedisFloorStore store, FloorControlService floor) {
        this.store = store;
        this.floor = floor;
    }

    @Scheduled(fixedDelayString = "${talkburst.floor.sweep-interval:PT0.5S}")
    public void sweepExpiredGrants() {
        for (String sessionId : store.dueSessions()) {
            try {
                floor.sweep(sessionId);
            } catch (RuntimeException e) {
                log.warn("floor sweep failed for session {}", sessionId, e);
            }
        }
    }
}
