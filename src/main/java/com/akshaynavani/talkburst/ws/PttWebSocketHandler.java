package com.akshaynavani.talkburst.ws;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.FloorState;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.Role;
import com.akshaynavani.talkburst.events.EventBus;
import com.akshaynavani.talkburst.events.SessionEnvelope.ParticipantChange;
import com.akshaynavani.talkburst.events.SessionEventListener;
import com.akshaynavani.talkburst.floor.FloorControlService;
import com.akshaynavani.talkburst.floor.FloorDecision;
import com.akshaynavani.talkburst.floor.FloorEvent;
import com.akshaynavani.talkburst.floor.RedisFloorStore;
import com.akshaynavani.talkburst.protocol.ClientMessage;
import com.akshaynavani.talkburst.protocol.ProtocolCodec;
import com.akshaynavani.talkburst.protocol.ServerMessage;
import com.akshaynavani.talkburst.protocol.ServerMessageType;
import com.akshaynavani.talkburst.session.SessionFullException;
import com.akshaynavani.talkburst.session.SessionNotFoundException;
import com.akshaynavani.talkburst.session.SessionService;

/**
 * PTT signalling endpoint.
 *
 * <p>Text frames are JSON control messages ({@link ClientMessage}); binary frames are
 * opaque audio chunks relayed to every other participant, but only while the sender
 * holds the floor. Floor grants are announced via the event bus rather than in the
 * direct reply, so the requester and everyone else observe the same ordering.
 */
@Component
public class PttWebSocketHandler extends AbstractWebSocketHandler implements SessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(PttWebSocketHandler.class);
    private static final String ATTR_CONN = "tb.conn";
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final SessionService sessions;
    private final FloorControlService floor;
    private final ConnectionRegistry registry;
    private final EventBus bus;
    private final ProtocolCodec codec;
    private final Clock clock;

    public PttWebSocketHandler(SessionService sessions, FloorControlService floor, ConnectionRegistry registry,
                               EventBus bus, ProtocolCodec codec, Clock clock) {
        this.sessions = sessions;
        this.floor = floor;
        this.registry = registry;
        this.bus = bus;
        this.codec = codec;
        this.clock = clock;
        bus.setListener(this);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String sessionId = (String) raw.getAttributes().get(PttHandshakeInterceptor.ATTR_SESSION);
        Participant participant = new Participant(
                (String) raw.getAttributes().get(PttHandshakeInterceptor.ATTR_USER),
                (String) raw.getAttributes().get(PttHandshakeInterceptor.ATTR_NAME),
                (Role) raw.getAttributes().get(PttHandshakeInterceptor.ATTR_ROLE));
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS,
                SEND_BUFFER_LIMIT, ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);

        boolean freshJoin;
        try {
            freshJoin = sessions.join(sessionId, participant);
        } catch (SessionNotFoundException | SessionFullException e) {
            String code = e instanceof SessionFullException ? "SESSION_FULL" : "SESSION_NOT_FOUND";
            socket.sendMessage(text(ServerMessage.error(sessionId, null, code, e.getMessage())));
            socket.close(CloseStatus.POLICY_VIOLATION.withReason(code));
            return;
        }

        PttConnection previous = registry.findByUser(sessionId, participant.userId()).orElse(null);
        PttConnection conn = new PttConnection(socket, sessionId, participant);
        raw.getAttributes().put(ATTR_CONN, conn);
        registry.add(conn);
        if (previous != null) {
            // Same user reconnected to this node: the new socket takes over.
            previous.socket().close(new CloseStatus(4000, "replaced by newer connection"));
        }

        FloorState state = floor.state(sessionId);
        if (state.holder() != null && state.holder().userId().equals(participant.userId())) {
            conn.setHeldGrant(state.holder());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("self", participant);
        data.put("participants", sessions.members(sessionId));
        data.put("floor", floorData(state.holder(), conn, null));
        data.put("queue", state.queue());
        sendTo(conn, ServerMessage.of(ServerMessageType.WELCOME, sessionId, participant.userId(), data));

        if (freshJoin) {
            bus.publishParticipant(sessionId, ParticipantChange.Change.JOINED, participant);
        }
        log.info("session={} user={} role={} connected ({})", sessionId, participant.userId(),
                participant.role(), freshJoin ? "joined" : "rejoined");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        PttConnection conn = (PttConnection) raw.getAttributes().get(ATTR_CONN);
        if (conn == null) {
            return;
        }
        registry.remove(conn.sessionId(), conn.id());
        if (registry.findByUser(conn.sessionId(), conn.userId()).isPresent()) {
            return; // superseded by a newer socket for the same user
        }
        try {
            floor.onParticipantLeft(conn.sessionId(), conn.participant());
            if (sessions.leave(conn.sessionId(), conn.userId())) {
                bus.publishParticipant(conn.sessionId(), ParticipantChange.Change.LEFT, conn.participant());
            }
        } catch (RuntimeException e) {
            log.warn("cleanup failed for session={} user={}", conn.sessionId(), conn.userId(), e);
        }
        log.info("session={} user={} disconnected ({})", conn.sessionId(), conn.userId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error on {}: {}", session.getId(), exception.toString());
    }

    // ---------------------------------------------------------------- inbound

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        PttConnection conn = (PttConnection) raw.getAttributes().get(ATTR_CONN);
        if (conn == null) {
            return;
        }
        ClientMessage msg;
        try {
            msg = codec.decode(message.getPayload());
        } catch (ProtocolCodec.ProtocolException e) {
            sendTo(conn, ServerMessage.error(conn.sessionId(), null, "BAD_MESSAGE", e.getMessage()));
            return;
        }
        String sid = conn.sessionId();
        String rid = msg.requestId();
        switch (msg.type()) {
            case FLOOR_REQUEST -> onFloorRequest(conn, rid);
            case FLOOR_RELEASE -> {
                String token = msg.token() != null ? msg.token()
                        : conn.heldGrant() != null ? conn.heldGrant().token() : null;
                if (token == null || !floor.release(sid, token)) {
                    sendTo(conn, ServerMessage.error(sid, rid, "NOT_HOLDER", "you do not hold the floor"));
                }
            }
            case FLOOR_RENEW -> onRenew(conn, msg, rid);
            case FLOOR_CANCEL -> {
                boolean removed = floor.cancel(sid, conn.userId());
                if (!removed) {
                    sendTo(conn, ServerMessage.error(sid, rid, "NOT_QUEUED", "no pending floor request"));
                }
            }
            case STATE -> {
                FloorState state = floor.state(sid);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("floor", floorData(state.holder(), conn, null));
                data.put("queue", state.queue());
                data.put("participants", sessions.members(sid));
                sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_STATE, sid, null, data).withRequestId(rid));
            }
            case PING -> {
                sessions.touch(sid);
                sendTo(conn, ServerMessage.of(ServerMessageType.PONG, sid, null, null).withRequestId(rid));
            }
        }
    }

    private void onFloorRequest(PttConnection conn, String rid) {
        String sid = conn.sessionId();
        sessions.touch(sid);
        FloorDecision d = floor.request(sid, conn.userId());
        switch (d.outcome()) {
            case GRANTED -> {
                // Announced to everyone (including us) through the event bus; cache eagerly
                // so the first audio frame is not rejected while the event is in flight.
                conn.setHeldGrant(d.grant());
            }
            case ALREADY_HOLDING -> {
                conn.setHeldGrant(d.grant());
                sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_GRANTED, sid, conn.userId(),
                        floorData(d.grant(), conn, null)).withRequestId(rid));
            }
            case QUEUED -> sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_QUEUED, sid, conn.userId(),
                    Map.of("position", d.queuePosition())).withRequestId(rid));
            case QUEUE_FULL, DENIED -> sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_DENIED, sid,
                    conn.userId(), Map.of("reason", d.reason())).withRequestId(rid));
        }
    }

    private void onRenew(PttConnection conn, ClientMessage msg, String rid) {
        String sid = conn.sessionId();
        String token = msg.token() != null ? msg.token()
                : conn.heldGrant() != null ? conn.heldGrant().token() : null;
        if (token == null) {
            sendTo(conn, ServerMessage.error(sid, rid, "NOT_HOLDER", "you do not hold the floor"));
            return;
        }
        RedisFloorStore.RenewResult r = floor.renew(sid, token);
        switch (r.status()) {
            case "RENEWED" -> {
                conn.setHeldGrant(r.grant());
                sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_RENEWED, sid, conn.userId(),
                        Map.of("expiresAt", r.grant().expiresAt())).withRequestId(rid));
            }
            case "MAX_BURST" -> sendTo(conn, ServerMessage.of(ServerMessageType.FLOOR_DENIED, sid, conn.userId(),
                    Map.of("reason", "maximum talk burst reached", "expiresAt", r.grant().expiresAt()))
                    .withRequestId(rid));
            default -> {
                conn.setHeldGrant(null);
                sendTo(conn, ServerMessage.error(sid, rid, "NOT_HOLDER", "you do not hold the floor"));
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) {
        PttConnection conn = (PttConnection) raw.getAttributes().get(ATTR_CONN);
        if (conn == null) {
            return;
        }
        if (!conn.holdsFloor(clock.instant())) {
            if (conn.shouldNotifyAudioReject(clock.millis())) {
                sendTo(conn, ServerMessage.error(conn.sessionId(), null, "NOT_HOLDER",
                        "audio dropped: you do not hold the floor"));
            }
            return;
        }
        byte[] frame = new byte[message.getPayloadLength()];
        message.getPayload().get(frame);
        bus.publishAudio(conn.sessionId(), conn.userId(), frame);
    }

    // ---------------------------------------------------------------- bus -> sockets

    @Override
    public void onFloorEvent(FloorEvent event) {
        String sid = event.sessionId();
        if (event.type() == FloorEvent.Type.QUEUE_UPDATED) {
            broadcast(sid, ServerMessage.of(ServerMessageType.FLOOR_QUEUE_UPDATED, sid, null,
                    Map.of("queue", event.queue() == null ? List.of() : event.queue())));
            return;
        }
        FloorGrant g = event.grant();
        ServerMessageType type = switch (event.type()) {
            case GRANTED -> ServerMessageType.FLOOR_GRANTED;
            case RELEASED -> ServerMessageType.FLOOR_RELEASED;
            case REVOKED -> ServerMessageType.FLOOR_REVOKED;
            case EXPIRED -> ServerMessageType.FLOOR_EXPIRED;
            case QUEUE_UPDATED -> throw new IllegalStateException();
        };
        for (PttConnection conn : registry.inSession(sid)) {
            if (event.type() == FloorEvent.Type.GRANTED) {
                if (conn.userId().equals(g.userId())) {
                    conn.setHeldGrant(g);
                }
            } else {
                conn.clearGrant(g.token());
            }
            sendTo(conn, ServerMessage.of(type, sid, g.userId(), floorData(g, conn, event.reason())));
        }
    }

    @Override
    public void onParticipantChange(String sessionId, ParticipantChange change) {
        ServerMessageType type = change.change() == ParticipantChange.Change.JOINED
                ? ServerMessageType.PARTICIPANT_JOINED : ServerMessageType.PARTICIPANT_LEFT;
        Participant p = change.participant();
        broadcast(sessionId, ServerMessage.of(type, sessionId, p.userId(),
                Map.of("displayName", p.displayName(), "role", p.role().name())));
    }

    @Override
    public void onAudio(String sessionId, String senderUserId, byte[] frame) {
        BinaryMessage msg = new BinaryMessage(frame);
        for (PttConnection conn : registry.inSession(sessionId)) {
            if (!conn.userId().equals(senderUserId)) {
                sendRaw(conn, msg);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Floor fields for a client; the token is only ever revealed to its owner. */
    private static Map<String, Object> floorData(FloorGrant g, PttConnection viewer, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (g != null) {
            data.put("holder", g.userId());
            data.put("role", g.role().name());
            data.put("grantedAt", g.grantedAt());
            data.put("expiresAt", g.expiresAt());
            if (viewer.userId().equals(g.userId())) {
                data.put("token", g.token());
            }
        }
        if (reason != null) {
            data.put("reason", reason);
        }
        return data;
    }

    private void broadcast(String sessionId, ServerMessage message) {
        TextMessage text = text(message);
        for (PttConnection conn : registry.inSession(sessionId)) {
            sendRaw(conn, text);
        }
    }

    private void sendTo(PttConnection conn, ServerMessage message) {
        sendRaw(conn, text(message));
    }

    private void sendRaw(PttConnection conn, org.springframework.web.socket.WebSocketMessage<?> message) {
        if (!conn.isOpen()) {
            return;
        }
        try {
            conn.send(message);
        } catch (IOException | IllegalStateException e) {
            log.debug("send to {} failed: {}", conn.userId(), e.toString());
        }
    }

    private TextMessage text(ServerMessage message) {
        return new TextMessage(codec.encode(message));
    }
}
