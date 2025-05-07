package com.akshaynavani.talkburst.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "talkburst")
public record TalkBurstProperties(
        @DefaultValue("local") String instanceId,
        @DefaultValue Floor floor,
        @DefaultValue Session session,
        @DefaultValue Ws ws) {

    public record Floor(
            @DefaultValue("PT10S") Duration grantTtl,
            @DefaultValue("PT30S") Duration maxBurst,
            @DefaultValue("16") int maxQueue,
            @DefaultValue("PT0.5S") Duration sweepInterval) {
    }

    public record Session(
            @DefaultValue("PT2H") Duration idleTtl,
            @DefaultValue("64") int maxParticipants) {
    }

    public record Ws(
            @DefaultValue("*") List<String> allowedOrigins,
            @DefaultValue("16384") int maxAudioFrameBytes) {
    }
}
