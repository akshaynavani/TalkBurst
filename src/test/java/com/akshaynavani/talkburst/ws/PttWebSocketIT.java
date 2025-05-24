package com.akshaynavani.talkburst.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.CloseStatus;

import com.akshaynavani.talkburst.TalkBurstApplication;
import com.akshaynavani.talkburst.support.RedisContainerSupport;
import com.akshaynavani.talkburst.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "talkburst.floor.grant-ttl=PT5S",
        "talkburst.floor.max-burst=PT10S",
        "talkburst.instance-id=node-a"
})
class PttWebSocketIT extends RedisContainerSupport {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String sid;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void createSession() {
        var resp = rest.postForEntity("/api/sessions",
                Map.of("name", "squad", "createdBy", "it", "maxParticipants", 16), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        sid = resp.getBody().get("id").asText();
    }

    @AfterEach
    void closeClients() throws Exception {
        for (TestClient c : clients) {
            c.close();
        }
        clients.clear();
    }

    private TestClient join(String user, String role) throws Exception {
        return join(port, user, role);
    }

    private TestClient join(int p, String user, String role) throws Exception {
        TestClient c = TestClient.connect(p, sid, user, role);
        clients.add(c);
        c.await("WELCOME");
        return c;
    }

    private static boolean holder(JsonNode m, String user) {
        return user.equals(m.path("data").path("holder").asText());
    }

    @Test
    void grantIsBroadcastButTokenOnlyGoesToHolder() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");

        alice.send("FLOOR_REQUEST");

        JsonNode mine = alice.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        JsonNode theirs = bob.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        assertThat(mine.path("data").path("token").asText()).isNotBlank();
        assertThat(theirs.path("data").has("token")).isFalse();
    }

    @Test
    void secondRequesterIsQueuedAndPromotedOnRelease() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");

        alice.send("FLOOR_REQUEST");
        alice.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        bob.send("FLOOR_REQUEST", Map.of("requestId", "b1"));

        JsonNode queued = bob.await("FLOOR_QUEUED");
        assertThat(queued.path("requestId").asText()).isEqualTo("b1");
        assertThat(queued.path("data").path("position").asInt()).isEqualTo(1);

        alice.send("FLOOR_RELEASE");
        bob.await("FLOOR_RELEASED", m -> holder(m, "alice"));
        JsonNode promoted = bob.await("FLOOR_GRANTED", m -> holder(m, "bob"));
        assertThat(promoted.path("data").path("token").asText()).isNotBlank();
    }

    @Test
    void audioFromHolderIsRelayedToEveryoneElseOnly() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");
        TestClient lee = join("lee", "LISTENER");

        alice.send("FLOOR_REQUEST");
        alice.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        byte[] frame = {1, 2, 3, 4, 5};
        alice.sendAudio(frame);

        await().atMost(Duration.ofSeconds(5)).until(() -> bob.audio().size() == 1 && lee.audio().size() == 1);
        assertThat(bob.audio().get(0)).containsExactly(frame);
        Thread.sleep(200);
        assertThat(alice.audio()).isEmpty();
    }

    @Test
    void audioFromNonHolderIsDropped() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");

        bob.sendAudio(new byte[] {9, 9});

        JsonNode err = bob.await("ERROR");
        assertThat(err.path("data").path("code").asText()).isEqualTo("NOT_HOLDER");
        Thread.sleep(300);
        assertThat(alice.audio()).isEmpty();
    }

    @Test
    void listenerRequestIsDenied() throws Exception {
        TestClient lee = join("lee", "LISTENER");
        lee.send("FLOOR_REQUEST");
        assertThat(lee.await("FLOOR_DENIED").path("data").path("reason").asText()).contains("receive-only");
    }

    @Test
    void dispatcherPreemptsAndTalkerIsTold() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient dan = join("dan", "DISPATCHER");

        alice.send("FLOOR_REQUEST");
        alice.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        dan.send("FLOOR_REQUEST");

        JsonNode revoked = alice.await("FLOOR_REVOKED", m -> holder(m, "alice"));
        assertThat(revoked.path("data").path("reason").asText()).contains("dan");
        dan.await("FLOOR_GRANTED", m -> holder(m, "dan"));

        // alice's cached grant is gone, so her audio is now rejected.
        alice.sendAudio(new byte[] {1});
        assertThat(alice.await("ERROR").path("data").path("code").asText()).isEqualTo("NOT_HOLDER");
    }

    @Test
    void concurrentPressesYieldExactlyOneTalker() throws Exception {
        int n = 8;
        List<TestClient> group = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            group.add(join("p" + i, "MEMBER"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        for (TestClient c : group) {
            pool.submit(() -> {
                go.await();
                c.send("FLOOR_REQUEST");
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Everyone sees the same single holder; everyone else got queued.
        Set<String> holders = new HashSet<>();
        for (TestClient c : group) {
            holders.add(c.await("FLOOR_GRANTED").path("data").path("holder").asText());
        }
        assertThat(holders).hasSize(1);
        String winner = holders.iterator().next();
        for (TestClient c : group) {
            if (!c.user.equals(winner)) {
                c.await("FLOOR_QUEUED");
            }
        }
        JsonNode floor = rest.getForObject("/api/sessions/" + sid + "/floor", JsonNode.class);
        assertThat(floor.path("holder").path("userId").asText()).isEqualTo(winner);
        assertThat(floor.path("holder").has("token")).isFalse();
        assertThat(floor.path("queue")).hasSize(n - 1);
    }

    @Test
    void holderDisconnectHandsFloorToNextInQueue() throws Exception {
        TestClient alice = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");

        alice.send("FLOOR_REQUEST");
        bob.await("FLOOR_GRANTED", m -> holder(m, "alice"));
        bob.send("FLOOR_REQUEST");
        bob.await("FLOOR_QUEUED");

        alice.close();

        bob.await("PARTICIPANT_LEFT", m -> "alice".equals(m.path("userId").asText()));
        bob.await("FLOOR_GRANTED", m -> holder(m, "bob"));
    }

    @Test
    void reconnectReplacesOldSocketWithoutLeaving() throws Exception {
        TestClient first = join("alice", "MEMBER");
        TestClient bob = join("bob", "MEMBER");
        TestClient second = join("alice", "MEMBER");

        await().atMost(Duration.ofSeconds(5)).until(() -> first.closeStatus() != null);
        assertThat(first.closeStatus().getCode()).isEqualTo(4000);
        Thread.sleep(300);
        assertThat(bob.hasMessage("PARTICIPANT_LEFT")).isFalse();
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void joiningUnknownSessionIsRefused() throws Exception {
        TestClient c = TestClient.connect(port, "does-not-exist", "zed", "MEMBER");
        clients.add(c);
        assertThat(c.await("ERROR").path("data").path("code").asText()).isEqualTo("SESSION_NOT_FOUND");
        await().atMost(Duration.ofSeconds(5)).until(() -> c.closeStatus() != null);
        assertThat(c.closeStatus().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void floorAndAudioCrossServiceInstancesViaRedis() throws Exception {
        try (ConfigurableApplicationContext nodeB = new SpringApplicationBuilder(TalkBurstApplication.class)
                .properties(
                        "server.port=0",
                        "talkburst.instance-id=node-b",
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getMappedPort(6379))
                .run()) {
            int portB = Integer.parseInt(nodeB.getEnvironment().getProperty("local.server.port"));

            TestClient alice = join(port, "alice", "MEMBER");
            TestClient bob = join(portB, "bob", "MEMBER");
            alice.await("PARTICIPANT_JOINED", m -> "bob".equals(m.path("userId").asText()));

            alice.send("FLOOR_REQUEST");
            alice.await("FLOOR_GRANTED", m -> holder(m, "alice"));
            bob.await("FLOOR_GRANTED", m -> holder(m, "alice"));

            bob.send("FLOOR_REQUEST");
            bob.await("FLOOR_QUEUED");

            alice.sendAudio(new byte[] {7, 7, 7});
            await().atMost(Duration.ofSeconds(5)).until(() -> bob.audio().size() == 1);

            alice.send("FLOOR_RELEASE");
            JsonNode granted = bob.await("FLOOR_GRANTED", m -> holder(m, "bob"));
            assertThat(granted.path("data").path("token").asText()).isNotBlank();
            alice.await("FLOOR_GRANTED", m -> holder(m, "bob"));
        }
    }
}
