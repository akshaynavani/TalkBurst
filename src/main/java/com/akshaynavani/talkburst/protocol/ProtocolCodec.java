package com.akshaynavani.talkburst.protocol;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON (de)serialisation for control frames, isolated so the handler stays thin. */
@Component
public class ProtocolCodec {

    private final ObjectMapper mapper;

    public ProtocolCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ClientMessage decode(String payload) {
        try {
            ClientMessage msg = mapper.readValue(payload, ClientMessage.class);
            if (msg.type() == null) {
                throw new ProtocolException("missing 'type'");
            }
            return msg;
        } catch (JsonProcessingException e) {
            throw new ProtocolException("malformed message: " + e.getOriginalMessage());
        }
    }

    public String encode(ServerMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode server message", e);
        }
    }

    public static class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
