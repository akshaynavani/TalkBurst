package com.akshaynavani.talkburst.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

class ProtocolCodecTest {

    private final ProtocolCodec codec = new ProtocolCodec(new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void decodesControlMessageAndIgnoresUnknownFields() {
        ClientMessage m = codec.decode("{\"type\":\"FLOOR_RELEASE\",\"requestId\":\"7\",\"token\":\"t\",\"x\":1}");
        assertThat(m.type()).isEqualTo(ClientMessageType.FLOOR_RELEASE);
        assertThat(m.requestId()).isEqualTo("7");
        assertThat(m.token()).isEqualTo("t");
    }

    @Test
    void rejectsMissingTypeUnknownTypeAndGarbage() {
        assertThatThrownBy(() -> codec.decode("{}")).isInstanceOf(ProtocolCodec.ProtocolException.class);
        assertThatThrownBy(() -> codec.decode("{\"type\":\"SHOUT\"}"))
                .isInstanceOf(ProtocolCodec.ProtocolException.class);
        assertThatThrownBy(() -> codec.decode("not json")).isInstanceOf(ProtocolCodec.ProtocolException.class);
    }

    @Test
    void encodesServerMessageWithoutNullFields() {
        String json = codec.encode(ServerMessage.of(ServerMessageType.FLOOR_QUEUED, "s1", "bob",
                Map.of("position", 2)).withRequestId("r1"));
        assertThat(json)
                .contains("\"type\":\"FLOOR_QUEUED\"")
                .contains("\"position\":2")
                .contains("\"requestId\":\"r1\"")
                .doesNotContain("null");
    }
}
