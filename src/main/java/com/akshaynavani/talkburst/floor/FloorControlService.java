package com.akshaynavani.talkburst.floor;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.FloorState;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.floor.FloorEvent.Type;
import com.akshaynavani.talkburst.session.SessionRepository;

/**
 * Floor-control policy: who may talk, who waits, who gets pre-empted.
 * All state transitions are atomic in Redis; this class decides which
 * transition to attempt and which events to broadcast afterwards.
 */
@Service
public class FloorControlService {

    private static final Logger log = LoggerFactory.getLogger(FloorControlService.class);

    private final RedisFloorStore store;
    private final SessionRepository sessions;
    private final FloorEventPublisher events;

    public FloorControlService(RedisFloorStore store, SessionRepository sessions, FloorEventPublisher events) {
        this.store = store;
        this.sessions = sessions;
        this.events = events;
    }

    public FloorDecision request(String sessionId, String userId) {
        Optional<Participant> member = sessions.findMember(sessionId, userId);
        if (member.isEmpty()) {
            return FloorDecision.denied("not a member of this session");
        }
        Participant p = member.get();
        if (!p.role().canTalk()) {
            return FloorDecision.denied(p.role() + " is receive-only");
        }

        RedisFloorStore.AcquireResult r = store.acquire(sessionId, p);
        return switch (r.status()) {
            case "GRANTED" -> {
                announceExpired(r.previous());
                publish(Type.GRANTED, r.grant(), null);
                publishQueue(sessionId);
                yield FloorDecision.granted(r.grant());
            }
            case "HELD" -> FloorDecision.holding(r.grant());
            case "PREEMPTED" -> {
                log.info("session={} floor pre-empted: {} -> {}", sessionId, r.previous().userId(), userId);
                publish(Type.REVOKED, r.previous(), "pre-empted by " + userId + " (" + p.role() + ")");
                publish(Type.GRANTED, r.grant(), null);
                publishQueue(sessionId);
                yield FloorDecision.granted(r.grant());
            }
            case "QUEUED" -> {
                announceExpired(r.previous());
                if (r.grantedToOther() != null) {
                    publish(Type.GRANTED, r.grantedToOther(), null);
                }
                publishQueue(sessionId);
                yield FloorDecision.queued(r.rank() + 1);
            }
            case "QUEUE_FULL" -> FloorDecision.queueFull();
            default -> throw new IllegalStateException("unexpected acquire status " + r.status());
        };
    }

    /** Release by the holder, proven by the grant token. Returns false if they no longer hold it. */
    public boolean release(String sessionId, String token) {
        return handleRelease(store.releaseByToken(sessionId, token), "released");
    }

    public RedisFloorStore.RenewResult renew(String sessionId, String token) {
        return store.renew(sessionId, token);
    }

    /** Withdraw a pending request. */
    public boolean cancel(String sessionId, String userId) {
        boolean removed = sessions.findMember(sessionId, userId)
                .map(p -> store.dequeue(sessionId, p))
                .orElse(false);
        if (removed) {
            publishQueue(sessionId);
        }
        return removed;
    }

    /** Cleans up after a participant leaves or disconnects: drop queued request and free the floor. */
    public void onParticipantLeft(String sessionId, Participant p) {
        boolean dequeued = store.dequeue(sessionId, p);
        boolean released = handleRelease(store.releaseByUser(sessionId, p.userId()), "holder left");
        if (dequeued && !released) {
            publishQueue(sessionId);
        }
    }

    /** Invoked by the sweeper when a grant deadline has passed. */
    public void sweep(String sessionId) {
        RedisFloorStore.SweepResult r = store.sweep(sessionId);
        if (!"EXPIRED".equals(r.status())) {
            return;
        }
        announceExpired(r.expired());
        if (r.promoted() != null) {
            publish(Type.GRANTED, r.promoted(), null);
        }
        publishQueue(sessionId);
    }

    public FloorState state(String sessionId) {
        return new FloorState(sessionId, store.current(sessionId).orElse(null), store.queue(sessionId));
    }

    private boolean handleRelease(RedisFloorStore.ReleaseResult r, String reason) {
        if (!r.isReleased()) {
            return false;
        }
        publish(Type.RELEASED, r.released(), reason);
        if (r.promoted() != null) {
            publish(Type.GRANTED, r.promoted(), null);
        }
        publishQueue(r.released().sessionId());
        return true;
    }

    private void announceExpired(FloorGrant expired) {
        if (expired != null) {
            publish(Type.EXPIRED, expired, "talk burst time limit reached");
        }
    }

    private void publish(Type type, FloorGrant grant, String reason) {
        events.publish(FloorEvent.of(type, grant, reason));
    }

    private void publishQueue(String sessionId) {
        events.publish(FloorEvent.queue(sessionId, store.queue(sessionId)));
    }
}
