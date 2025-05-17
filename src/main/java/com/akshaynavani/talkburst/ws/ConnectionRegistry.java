package com.akshaynavani.talkburst.ws;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.akshaynavani.talkburst.events.EventBus;

/**
 * Tracks connections held by this node, grouped by session, and keeps the event-bus
 * subscription for a session alive exactly while at least one local socket is in it.
 */
@Component
public class ConnectionRegistry {

    private final Map<String, Map<String, PttConnection>> bySession = new ConcurrentHashMap<>();
    private final EventBus bus;

    public ConnectionRegistry(EventBus bus) {
        this.bus = bus;
    }

    public void add(PttConnection conn) {
        bySession.compute(conn.sessionId(), (sid, conns) -> {
            if (conns == null) {
                conns = new ConcurrentHashMap<>();
                bus.subscribe(sid);
            }
            conns.put(conn.id(), conn);
            return conns;
        });
    }

    public Optional<PttConnection> remove(String sessionId, String connectionId) {
        PttConnection[] removed = new PttConnection[1];
        bySession.computeIfPresent(sessionId, (sid, conns) -> {
            removed[0] = conns.remove(connectionId);
            if (conns.isEmpty()) {
                bus.unsubscribe(sid);
                return null;
            }
            return conns;
        });
        return Optional.ofNullable(removed[0]);
    }

    public Collection<PttConnection> inSession(String sessionId) {
        Map<String, PttConnection> conns = bySession.get(sessionId);
        return conns == null ? List.of() : conns.values();
    }

    public Optional<PttConnection> find(String sessionId, String connectionId) {
        return Optional.ofNullable(bySession.getOrDefault(sessionId, Map.of()).get(connectionId));
    }

    public Optional<PttConnection> findByUser(String sessionId, String userId) {
        return inSession(sessionId).stream().filter(c -> c.userId().equals(userId)).findFirst();
    }

    public int size() {
        return bySession.values().stream().mapToInt(Map::size).sum();
    }
}
