package com.akshaynavani.talkburst.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.Role;
import com.akshaynavani.talkburst.domain.TalkSession;

/**
 * Redis persistence for sessions and their members.
 *
 * <pre>
 * tb:session:{id}  HASH  name, createdBy, createdAt, maxParticipants
 * tb:members:{id}  HASH  userId -> "ROLE|displayName"
 * tb:sessions      SET   all known session ids
 * </pre>
 */
@Repository
public class SessionRepository {

    public enum JoinResult { JOINED, REJOINED, NO_SUCH_SESSION, FULL }

    private final StringRedisTemplate redis;
    private final RedisScript<Long> joinScript;

    public SessionRepository(StringRedisTemplate redis,
                             @Qualifier("joinSessionScript") RedisScript<Long> joinSessionScript) {
        this.redis = redis;
        this.joinScript = joinSessionScript;
    }

    public void save(TalkSession session, Duration ttl) {
        String key = RedisKeys.session(session.id());
        redis.opsForHash().putAll(key, Map.of(
                "name", session.name(),
                "createdBy", session.createdBy(),
                "createdAt", session.createdAt().toString(),
                "maxParticipants", Integer.toString(session.maxParticipants())));
        redis.expire(key, ttl);
        redis.opsForSet().add(RedisKeys.SESSION_INDEX, session.id());
    }

    public Optional<TalkSession> findById(String sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.session(sessionId));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TalkSession(
                sessionId,
                (String) h.get("name"),
                (String) h.get("createdBy"),
                Instant.parse((String) h.get("createdAt")),
                Integer.parseInt((String) h.get("maxParticipants"))));
    }

    /** Lists live sessions, lazily pruning index entries whose hash has expired. */
    public List<TalkSession> findAll() {
        Set<String> ids = redis.opsForSet().members(RedisKeys.SESSION_INDEX);
        List<TalkSession> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            findById(id).ifPresentOrElse(result::add,
                    () -> redis.opsForSet().remove(RedisKeys.SESSION_INDEX, id));
        }
        return result;
    }

    public void delete(String sessionId) {
        redis.delete(List.of(
                RedisKeys.session(sessionId),
                RedisKeys.members(sessionId),
                RedisKeys.floor(sessionId),
                RedisKeys.queue(sessionId)));
        redis.opsForSet().remove(RedisKeys.SESSION_INDEX, sessionId);
        redis.opsForZSet().remove(RedisKeys.ACTIVE_FLOORS, sessionId);
    }

    public JoinResult addMember(String sessionId, Participant p, int maxParticipants, Duration ttl) {
        Long rc = redis.execute(joinScript,
                List.of(RedisKeys.session(sessionId), RedisKeys.members(sessionId)),
                p.userId(), encode(p), Integer.toString(maxParticipants), Long.toString(ttl.toMillis()));
        long code = rc == null ? -1 : rc;
        return switch ((int) code) {
            case 1 -> JoinResult.JOINED;
            case 0 -> JoinResult.REJOINED;
            case -2 -> JoinResult.FULL;
            default -> JoinResult.NO_SUCH_SESSION;
        };
    }

    public boolean removeMember(String sessionId, String userId) {
        Long removed = redis.opsForHash().delete(RedisKeys.members(sessionId), userId);
        return removed != null && removed > 0;
    }

    public Optional<Participant> findMember(String sessionId, String userId) {
        Object raw = redis.opsForHash().get(RedisKeys.members(sessionId), userId);
        return Optional.ofNullable(raw).map(v -> decode(userId, (String) v));
    }

    public List<Participant> members(String sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.members(sessionId));
        List<Participant> out = new ArrayList<>(h.size());
        h.forEach((k, v) -> out.add(decode((String) k, (String) v)));
        return out;
    }

    public void touch(String sessionId, Duration ttl) {
        redis.expire(RedisKeys.session(sessionId), ttl);
        redis.expire(RedisKeys.members(sessionId), ttl);
    }

    static String encode(Participant p) {
        return p.role().name() + "|" + p.displayName();
    }

    static Participant decode(String userId, String value) {
        int sep = value.indexOf('|');
        Role role = Role.valueOf(value.substring(0, sep));
        return new Participant(userId, value.substring(sep + 1), role);
    }
}
