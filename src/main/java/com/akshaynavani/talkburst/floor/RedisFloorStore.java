package com.akshaynavani.talkburst.floor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.akshaynavani.talkburst.config.TalkBurstProperties;
import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.session.RedisKeys;

/** Thin typed wrapper around the floor Lua scripts. Holds no policy. */
@Component
@SuppressWarnings({"rawtypes", "unchecked"})
public class RedisFloorStore {

    /** Extra lifetime on the floor key so the sweeper, not Redis, decides expiry. */
    static final long GRACE_MS = 5_000;

    public record AcquireResult(String status, FloorGrant grant, FloorGrant previous,
                                int rank, FloorGrant grantedToOther) {
    }

    public record ReleaseResult(FloorGrant released, FloorGrant promoted) {
        public boolean isReleased() {
            return released != null;
        }
    }

    public record RenewResult(String status, FloorGrant grant) {
    }

    public record SweepResult(String status, FloorGrant expired, FloorGrant promoted) {
    }

    private final StringRedisTemplate redis;
    private final RedisScript<List> acquireScript;
    private final RedisScript<List> releaseScript;
    private final RedisScript<List> renewScript;
    private final RedisScript<List> sweepScript;
    private final TalkBurstProperties.Floor cfg;
    private final Clock clock;

    public RedisFloorStore(StringRedisTemplate redis,
                           @Qualifier("floorAcquireScript") RedisScript<List> acquireScript,
                           @Qualifier("floorReleaseScript") RedisScript<List> releaseScript,
                           @Qualifier("floorRenewScript") RedisScript<List> renewScript,
                           @Qualifier("floorSweepScript") RedisScript<List> sweepScript,
                           TalkBurstProperties props,
                           Clock clock) {
        this.redis = redis;
        this.acquireScript = acquireScript;
        this.releaseScript = releaseScript;
        this.renewScript = renewScript;
        this.sweepScript = sweepScript;
        this.cfg = props.floor();
        this.clock = clock;
    }

    public AcquireResult acquire(String sessionId, Participant p) {
        long now = clock.millis();
        List<String> r = run(acquireScript, keys3(sessionId),
                sessionId,
                FloorValueCodec.member(p),
                p.role().canPreempt() ? "1" : "0",
                newToken(),
                Long.toString(now),
                Long.toString(grantTtlMs()),
                Long.toString(GRACE_MS),
                Integer.toString(cfg.maxQueue()),
                Double.toString(FloorValueCodec.queueScore(p.role(), now)));
        String status = r.get(0);
        return switch (status) {
            case "GRANTED" -> new AcquireResult(status, grant(sessionId, r, 1), grant(sessionId, r, 2), -1, null);
            case "HELD" -> new AcquireResult(status, grant(sessionId, r, 1), null, -1, null);
            case "PREEMPTED" -> new AcquireResult(status, grant(sessionId, r, 1), grant(sessionId, r, 2), -1, null);
            case "QUEUED" -> new AcquireResult(status, null, grant(sessionId, r, 2),
                    Integer.parseInt(r.get(1)), grant(sessionId, r, 3));
            default -> new AcquireResult(status, null, null, -1, null);
        };
    }

    public ReleaseResult releaseByToken(String sessionId, String token) {
        return release(sessionId, "token", token);
    }

    public ReleaseResult releaseByUser(String sessionId, String userId) {
        return release(sessionId, "user", userId);
    }

    private ReleaseResult release(String sessionId, String mode, String match) {
        List<String> r = run(releaseScript, keys3(sessionId),
                sessionId, mode, match, newToken(),
                Long.toString(clock.millis()), Long.toString(grantTtlMs()), Long.toString(GRACE_MS));
        if (!"RELEASED".equals(r.get(0))) {
            return new ReleaseResult(null, null);
        }
        return new ReleaseResult(grant(sessionId, r, 1), grant(sessionId, r, 2));
    }

    public RenewResult renew(String sessionId, String token) {
        List<String> r = run(renewScript,
                List.of(RedisKeys.floor(sessionId), RedisKeys.ACTIVE_FLOORS),
                sessionId, token, Long.toString(clock.millis()), Long.toString(grantTtlMs()),
                Long.toString(cfg.maxBurst().toMillis()), Long.toString(GRACE_MS));
        return new RenewResult(r.get(0), grant(sessionId, r, 1));
    }

    public SweepResult sweep(String sessionId) {
        List<String> r = run(sweepScript, keys3(sessionId),
                sessionId, newToken(), Long.toString(clock.millis()),
                Long.toString(grantTtlMs()), Long.toString(GRACE_MS));
        return new SweepResult(r.get(0), grant(sessionId, r, 1), grant(sessionId, r, 2));
    }

    /** Current holder, ignoring a grant that has lapsed but not yet been swept. */
    public Optional<FloorGrant> current(String sessionId) {
        FloorGrant g = FloorValueCodec.grant(sessionId, redis.opsForValue().get(RedisKeys.floor(sessionId)));
        if (g == null || !g.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(g);
    }

    /** Waiting user ids, best-ranked first. */
    public List<String> queue(String sessionId) {
        Set<String> members = redis.opsForZSet().range(RedisKeys.queue(sessionId), 0, -1);
        if (members == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(members.size());
        members.forEach(m -> out.add(FloorValueCodec.userIdOfMember(m)));
        return out;
    }

    public boolean dequeue(String sessionId, Participant p) {
        Long removed = redis.opsForZSet().remove(RedisKeys.queue(sessionId), FloorValueCodec.member(p));
        return removed != null && removed > 0;
    }

    /** Sessions whose grant deadline has passed and need a sweep. */
    public Set<String> dueSessions() {
        Set<String> due = redis.opsForZSet().rangeByScore(RedisKeys.ACTIVE_FLOORS, 0, clock.millis());
        return due == null ? Set.of() : due;
    }

    private long grantTtlMs() {
        return Math.min(cfg.grantTtl().toMillis(), cfg.maxBurst().toMillis());
    }

    private List<String> run(RedisScript<List> script, List<String> keys, String... args) {
        List<String> r = redis.execute(script, keys, (Object[]) args);
        if (r == null || r.isEmpty()) {
            throw new IllegalStateException("empty reply from floor script");
        }
        return r;
    }

    private static List<String> keys3(String sessionId) {
        return List.of(RedisKeys.floor(sessionId), RedisKeys.queue(sessionId), RedisKeys.ACTIVE_FLOORS);
    }

    private static FloorGrant grant(String sessionId, List<String> r, int idx) {
        return idx < r.size() ? FloorValueCodec.grant(sessionId, r.get(idx)) : null;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
