package com.akshaynavani.talkburst.floor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.Role;

class FloorValueCodecTest {

    @Test
    void higherPriorityAlwaysScoresLowerRegardlessOfArrivalTime() {
        long now = 1_728_000_000_000L;
        double lateSupervisor = FloorValueCodec.queueScore(Role.SUPERVISOR, now + 3_600_000);
        double earlyMember = FloorValueCodec.queueScore(Role.MEMBER, now);
        assertThat(lateSupervisor).isLessThan(earlyMember);
    }

    @Test
    void sameRoleIsFirstComeFirstServed() {
        long now = 1_728_000_000_000L;
        assertThat(FloorValueCodec.queueScore(Role.MEMBER, now))
                .isLessThan(FloorValueCodec.queueScore(Role.MEMBER, now + 1));
    }

    @Test
    void scoresStayExactInDoublePrecision() {
        long now = 4_102_444_800_000L; // year 2100
        double score = FloorValueCodec.queueScore(Role.LISTENER, now);
        assertThat((long) score - 3 * (long) FloorValueCodec.PRIORITY_BAND).isEqualTo(now);
    }

    @Test
    void memberEncodingRoundTripsUserId() {
        String m = FloorValueCodec.member(new Participant("alice", "Alice", Role.SUPERVISOR));
        assertThat(m).isEqualTo("alice|SUPERVISOR|1");
        assertThat(FloorValueCodec.userIdOfMember(m)).isEqualTo("alice");
    }

    @Test
    void parsesFloorValue() {
        FloorGrant g = FloorValueCodec.grant("s1", "bob|MEMBER|2|tok123|1000|11000");
        assertThat(g.sessionId()).isEqualTo("s1");
        assertThat(g.userId()).isEqualTo("bob");
        assertThat(g.role()).isEqualTo(Role.MEMBER);
        assertThat(g.token()).isEqualTo("tok123");
        assertThat(g.grantedAt()).isEqualTo(Instant.ofEpochMilli(1000));
        assertThat(g.expiresAt()).isEqualTo(Instant.ofEpochMilli(11000));
    }

    @Test
    void emptyValueMeansNoGrantAndCorruptValueFails() {
        assertThat(FloorValueCodec.grant("s1", "")).isNull();
        assertThat(FloorValueCodec.grant("s1", null)).isNull();
        assertThatThrownBy(() -> FloorValueCodec.grant("s1", "bob|MEMBER"))
                .isInstanceOf(IllegalStateException.class);
    }
}
