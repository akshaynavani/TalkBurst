package com.akshaynavani.talkburst.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

import com.akshaynavani.talkburst.config.TalkBurstProperties;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.floor.FloorEvent;
import com.akshaynavani.talkburst.session.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis pub/sub implementation of {@link EventBus}.
 *
 * <ul>
 *   <li>{@code tb:events:{sid}} carries JSON {@link SessionEnvelope}s.</li>
 *   <li>{@code tb:audio:{sid}} carries binary frames:
 *       {@code [u16 senderLen][sender UTF-8][audio bytes]}.</li>
 * </ul>
 */
@Component
public class RedisEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final ObjectMapper mapper;
    private final String instanceId;
    private final Map<String, MessageListener> subscriptions = new ConcurrentHashMap<>();
    private volatile SessionEventListener listener;

    public RedisEventBus(StringRedisTemplate redis, RedisMessageListenerContainer container,
                         ObjectMapper mapper, TalkBurstProperties props) {
        this.redis = redis;
        this.container = container;
        this.mapper = mapper;
        this.instanceId = props.instanceId();
    }

    @Override
    public void setListener(SessionEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void publish(FloorEvent event) {
        publishEnvelope(new SessionEnvelope(SessionEnvelope.Kind.FLOOR, event.sessionId(), instanceId, event, null));
    }

    @Override
    public void publishParticipant(String sessionId, SessionEnvelope.ParticipantChange.Change change, Participant p) {
        publishEnvelope(new SessionEnvelope(SessionEnvelope.Kind.PARTICIPANT, sessionId, instanceId, null,
                new SessionEnvelope.ParticipantChange(change, p)));
    }

    @Override
    public void publishAudio(String sessionId, String senderUserId, byte[] frame) {
        byte[] sender = senderUserId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + sender.length + frame.length);
        buf.putShort((short) sender.length).put(sender).put(frame);
        byte[] channel = RedisKeys.audio(sessionId).getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Long>) c -> c.publish(channel, buf.array()));
    }

    @Override
    public void subscribe(String sessionId) {
        subscriptions.computeIfAbsent(sessionId, sid -> {
            MessageListener l = this::onMessage;
            container.addMessageListener(l, topics(sid));
            log.debug("subscribed to session {}", sid);
            return l;
        });
    }

    @Override
    public void unsubscribe(String sessionId) {
        MessageListener l = subscriptions.remove(sessionId);
        if (l != null) {
            container.removeMessageListener(l, topics(sessionId));
            log.debug("unsubscribed from session {}", sessionId);
        }
    }

    private void publishEnvelope(SessionEnvelope envelope) {
        try {
            redis.convertAndSend(RedisKeys.events(envelope.sessionId()), mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode envelope", e);
        }
    }

    private void onMessage(Message message, byte[] pattern) {
        SessionEventListener target = listener;
        if (target == null) {
            return;
        }
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String sessionId = RedisKeys.sessionIdFromChannel(channel);
        try {
            if (channel.startsWith(RedisKeys.PREFIX + "audio:")) {
                ByteBuffer buf = ByteBuffer.wrap(message.getBody());
                int len = Short.toUnsignedInt(buf.getShort());
                byte[] sender = new byte[len];
                buf.get(sender);
                byte[] frame = new byte[buf.remaining()];
                buf.get(frame);
                target.onAudio(sessionId, new String(sender, StandardCharsets.UTF_8), frame);
                return;
            }
            SessionEnvelope env = mapper.readValue(message.getBody(), SessionEnvelope.class);
            switch (env.kind()) {
                case FLOOR -> target.onFloorEvent(env.floor());
                case PARTICIPANT -> target.onParticipantChange(sessionId, env.participant());
            }
        } catch (Exception e) {
            log.warn("dropping malformed message on {}", channel, e);
        }
    }

    private static List<Topic> topics(String sessionId) {
        return List.of(new ChannelTopic(RedisKeys.events(sessionId)), new ChannelTopic(RedisKeys.audio(sessionId)));
    }
}
