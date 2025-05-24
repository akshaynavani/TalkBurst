package com.akshaynavani.talkburst.floor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import com.akshaynavani.talkburst.domain.FloorGrant;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.Role;
import com.akshaynavani.talkburst.domain.TalkSession;
import com.akshaynavani.talkburst.events.RedisEventBus;
import com.akshaynavani.talkburst.floor.FloorDecision.Outcome;
import com.akshaynavani.talkburst.session.SessionService;
import com.akshaynavani.talkburst.support.RedisContainerSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "talkburst.floor.grant-ttl=PT1S",
        "talkburst.floor.max-burst=PT2S",
        "talkburst.floor.max-queue=32",
        "talkburst.floor.sweep-interval=PT0.1S"
})
class FloorControlServiceIT extends RedisContainerSupport {

    @Autowired
    SessionService sessions;

    @Autowired
    FloorControlService floor;

    @SpyBean
    RedisEventBus bus;

    String sid;

    @BeforeEach
    void createSession() {
        TalkSession s = sessions.create("it", "tester", 64);
        sid = s.id();
    }

    private void join(String user, Role role) {
        sessions.join(sid, new Participant(user, user, role));
    }

    @Test
    void firstRequesterGetsTheFloorAndOthersQueue() {
        join("alice", Role.MEMBER);
        join("bob", Role.MEMBER);

        FloorDecision a = floor.request(sid, "alice");
        FloorDecision b = floor.request(sid, "bob");

        assertThat(a.outcome()).isEqualTo(Outcome.GRANTED);
        assertThat(a.grant().userId()).isEqualTo("alice");
        assertThat(b.outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(b.queuePosition()).isEqualTo(1);
        assertThat(floor.state(sid).queue()).containsExactly("bob");
    }

    @Test
    void repeatedRequestByHolderIsIdempotent() {
        join("alice", Role.MEMBER);
        FloorDecision first = floor.request(sid, "alice");
        FloorDecision again = floor.request(sid, "alice");
        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_HOLDING);
        assertThat(again.grant().token()).isEqualTo(first.grant().token());
    }

    @Test
    void exactlyOneWinnerWhenManyClientsRaceForAnIdleFloor() throws Exception {
        int n = 24;
        for (int i = 0; i < n; i++) {
            join("u" + i, Role.MEMBER);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FloorDecision>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String user = "u" + i;
            Callable<FloorDecision> task = () -> {
                start.await();
                return floor.request(sid, user);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        int granted = 0;
        int queued = 0;
        for (Future<FloorDecision> f : futures) {
            switch (f.get().outcome()) {
                case GRANTED -> granted++;
                case QUEUED -> queued++;
                default -> throw new AssertionError("unexpected " + f.get());
            }
        }
        pool.shutdownNow();

        assertThat(granted).isEqualTo(1);
        assertThat(queued).isEqualTo(n - 1);
        assertThat(floor.state(sid).queue()).hasSize(n - 1);
    }

    @Test
    void supervisorJumpsAheadOfWaitingMembersOnRelease() {
        join("alice", Role.MEMBER);
        join("bob", Role.MEMBER);
        join("sue", Role.SUPERVISOR);

        FloorGrant alice = floor.request(sid, "alice").grant();
        floor.request(sid, "bob");
        FloorDecision sue = floor.request(sid, "sue");
        assertThat(sue.outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(sue.queuePosition()).isEqualTo(1);

        assertThat(floor.release(sid, alice.token())).isTrue();

        assertThat(floor.state(sid).holder().userId()).isEqualTo("sue");
        assertThat(floor.state(sid).queue()).containsExactly("bob");
    }

    @Test
    void dispatcherPreemptsCurrentTalker() {
        join("alice", Role.MEMBER);
        join("dan", Role.DISPATCHER);

        floor.request(sid, "alice");
        FloorDecision d = floor.request(sid, "dan");

        assertThat(d.outcome()).isEqualTo(Outcome.GRANTED);
        assertThat(floor.state(sid).holder().userId()).isEqualTo("dan");
        verify(bus, atLeastOnce()).publish(argThat(e ->
                e.type() == FloorEvent.Type.REVOKED && e.grant().userId().equals("alice")));
    }

    @Test
    void supervisorCannotPreempt() {
        join("alice", Role.MEMBER);
        join("sue", Role.SUPERVISOR);
        floor.request(sid, "alice");
        assertThat(floor.request(sid, "sue").outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(floor.state(sid).holder().userId()).isEqualTo("alice");
    }

    @Test
    void listenersAndStrangersAreDenied() {
        join("lee", Role.LISTENER);
        assertThat(floor.request(sid, "lee").outcome()).isEqualTo(Outcome.DENIED);
        assertThat(floor.request(sid, "nobody").outcome()).isEqualTo(Outcome.DENIED);
        assertThat(floor.state(sid).isIdle()).isTrue();
    }

    @Test
    void releaseWithWrongTokenIsRejected() {
        join("alice", Role.MEMBER);
        floor.request(sid, "alice");
        assertThat(floor.release(sid, "forged")).isFalse();
        assertThat(floor.state(sid).holder().userId()).isEqualTo("alice");
    }

    @Test
    void lapsedGrantIsSweptAndNextWaiterPromoted() {
        join("alice", Role.MEMBER);
        join("bob", Role.MEMBER);
        floor.request(sid, "alice");
        floor.request(sid, "bob");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(floor.state(sid).holder()).isNotNull()
                        .extracting(FloorGrant::userId).isEqualTo("bob"));
        verify(bus, atLeastOnce()).publish(argThat(e ->
                e.type() == FloorEvent.Type.EXPIRED && e.grant().userId().equals("alice")));
    }

    @Test
    void renewalNeverExtendsPastMaxBurst() throws Exception {
        join("alice", Role.MEMBER);
        FloorGrant g = floor.request(sid, "alice").grant();

        RedisFloorStore.RenewResult r = floor.renew(sid, g.token());
        assertThat(r.status()).isEqualTo("RENEWED");
        assertThat(r.grant().expiresAt()).isBeforeOrEqualTo(g.grantedAt().plusSeconds(2));

        // Keep renewing like a client that is still talking; the cap is grantedAt + 2s.
        Thread.sleep(700);
        assertThat(floor.renew(sid, g.token()).status()).isEqualTo("RENEWED");
        Thread.sleep(700);
        r = floor.renew(sid, g.token());
        assertThat(r.status()).isEqualTo("RENEWED");
        assertThat(r.grant().expiresAt()).isEqualTo(g.grantedAt().plusSeconds(2));

        Thread.sleep(300);
        assertThat(floor.renew(sid, g.token()).status()).isEqualTo("MAX_BURST");
    }

    @Test
    void leavingParticipantFreesFloorAndQueueSlot() {
        join("alice", Role.MEMBER);
        join("bob", Role.MEMBER);
        join("cat", Role.MEMBER);
        floor.request(sid, "alice");
        floor.request(sid, "bob");
        floor.request(sid, "cat");

        floor.onParticipantLeft(sid, new Participant("bob", "bob", Role.MEMBER));
        assertThat(floor.state(sid).queue()).containsExactly("cat");

        floor.onParticipantLeft(sid, new Participant("alice", "alice", Role.MEMBER));
        assertThat(floor.state(sid).holder().userId()).isEqualTo("cat");
        assertThat(floor.state(sid).queue()).isEmpty();
    }

    @Test
    void cancelWithdrawsQueuedRequest() {
        join("alice", Role.MEMBER);
        join("bob", Role.MEMBER);
        floor.request(sid, "alice");
        floor.request(sid, "bob");
        assertThat(floor.cancel(sid, "bob")).isTrue();
        assertThat(floor.cancel(sid, "bob")).isFalse();
        assertThat(floor.state(sid).queue()).isEmpty();
    }
}
