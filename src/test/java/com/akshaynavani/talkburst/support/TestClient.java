package com.akshaynavani.talkburst.support;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal PTT client for integration tests: records every frame it receives. */
public class TestClient extends AbstractWebSocketHandler implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);

    public final String user;
    private final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    private final List<byte[]> audio = new CopyOnWriteArrayList<>();
    private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
    private WebSocketSession session;

    private TestClient(String user) {
        this.user = user;
    }

    public static TestClient connect(int port, String sessionId, String user, String role) throws Exception {
        TestClient c = new TestClient(user);
        URI uri = URI.create("ws://localhost:" + port + "/ws/ptt?session=" + sessionId
                + "&user=" + user + "&role=" + role);
        c.session = new StandardWebSocketClient().execute(c, new WebSocketHttpHeaders(), uri).get(5, TimeUnit.SECONDS);
        return c;
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
        messages.add(MAPPER.readTree(message.getPayload()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) {
        ByteBuffer buf = message.getPayload();
        byte[] b = new byte[buf.remaining()];
        buf.get(b);
        audio.add(b);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closeStatus.set(status);
    }

    public synchronized void send(String type, Map<String, String> extra) throws IOException {
        var node = MAPPER.createObjectNode().put("type", type);
        extra.forEach(node::put);
        session.sendMessage(new TextMessage(node.toString()));
    }

    public void send(String type) throws IOException {
        send(type, Map.of());
    }

    public synchronized void sendAudio(byte[] frame) throws IOException {
        session.sendMessage(new BinaryMessage(frame));
    }

    /** Waits for (and consumes) the first message of {@code type} matching {@code filter}. */
    public JsonNode await(String type, Predicate<JsonNode> filter) {
        AtomicReference<JsonNode> hit = new AtomicReference<>();
        await().atMost(WAIT).until(() -> {
            for (JsonNode m : messages) {
                if (type.equals(m.path("type").asText()) && filter.test(m)) {
                    messages.remove(m);
                    hit.set(m);
                    return true;
                }
            }
            return false;
        });
        return hit.get();
    }

    public JsonNode await(String type) {
        return await(type, m -> true);
    }

    public boolean hasMessage(String type) {
        return messages.stream().anyMatch(m -> type.equals(m.path("type").asText()));
    }

    public List<byte[]> audio() {
        return new ArrayList<>(audio);
    }

    public CloseStatus closeStatus() {
        return closeStatus.get();
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
