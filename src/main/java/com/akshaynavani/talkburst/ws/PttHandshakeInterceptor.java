package com.akshaynavani.talkburst.ws;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.akshaynavani.talkburst.config.TalkBurstProperties;
import com.akshaynavani.talkburst.domain.Role;

/**
 * Validates {@code /ws/ptt?session=..&user=..&role=..&name=..&key=..} before upgrading
 * and stashes the parsed values as WebSocket session attributes.
 */
@Component
public class PttHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_SESSION = "tb.session";
    static final String ATTR_USER = "tb.user";
    static final String ATTR_ROLE = "tb.role";
    static final String ATTR_NAME = "tb.name";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final String privilegedKey;

    public PttHandshakeInterceptor(TalkBurstProperties props) {
        this.privilegedKey = props.ws().privilegedRoleKey();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String session = params.getFirst("session");
        String user = params.getFirst("user");
        String name = params.getFirst("name");
        Role role;
        try {
            role = Role.parse(params.getFirst("role"));
        } catch (IllegalArgumentException e) {
            return reject(response, HttpStatus.BAD_REQUEST);
        }
        if (session == null || user == null || !ID.matcher(session).matches() || !ID.matcher(user).matches()) {
            return reject(response, HttpStatus.BAD_REQUEST);
        }
        if ((role == Role.DISPATCHER || role == Role.SUPERVISOR) && !privilegedKeyMatches(params.getFirst("key"))) {
            return reject(response, HttpStatus.FORBIDDEN);
        }
        attributes.put(ATTR_SESSION, session);
        attributes.put(ATTR_USER, user);
        attributes.put(ATTR_ROLE, role);
        attributes.put(ATTR_NAME, sanitizeName(name, user));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private boolean privilegedKeyMatches(String presented) {
        if (privilegedKey == null || privilegedKey.isBlank()) {
            return true;
        }
        return presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), privilegedKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeName(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String cleaned = name.replace("|", "").strip();
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}
