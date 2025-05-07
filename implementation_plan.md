# TalkBurst — Implementation Plan

Push-to-talk (PTT) group communication service with floor control for real-time
group sessions.

**Stack:** Java 21, Spring Boot 3.3, Spring WebSocket (raw `TextWebSocketHandler` /
binary frames), Spring Data Redis (Lettuce), Maven, JUnit 5, Testcontainers.

## Key design decisions

- **One talker at a time per session ("the floor").** Floor ownership is a single Redis
  key `tb:floor:{sessionId}` acquired with `SET NX PX` so the grant is atomic across
  any number of service instances. Release/renew use Lua scripts that compare the
  owner token, so a stale client can never release someone else's floor.
- **Bounded talk bursts.** Each grant carries a TTL (default 30 s). Clients may not
  renew past the max burst; when the key expires the floor is free and the next
  queued requester is granted.
- **Queued requests with priority.** Waiting requesters are kept in a Redis sorted
  set `tb:queue:{sessionId}` scored by `(priority, enqueue time)`. Roles map to
  priorities, borrowed from telecom/dispatch PTT systems:
  `DISPATCHER` (can pre-empt), `SUPERVISOR`, `MEMBER`, `LISTENER` (receive-only).
- **Pre-emption.** A `DISPATCHER` may revoke the current floor from a lower-priority
  talker; the revoked talker gets `FLOOR_REVOKED`.
- **Signalling over WebSocket, audio as binary frames.** JSON text frames carry
  control messages (`JOIN`, `FLOOR_REQUEST`, `FLOOR_RELEASE`, ...); binary frames
  carry opaque audio chunks (e.g. Opus) which are relayed only if the sender
  currently holds the floor.
- **Horizontal scale via Redis pub/sub.** Every event and audio frame is published on
  `tb:events:{sessionId}`; each instance relays to its locally-connected sockets.
- **Session state in Redis.** Session metadata in a hash, members in a hash
  (`userId -> role`), all with an idle TTL that is refreshed on activity.

## Phases

### Phase 1 — Project scaffold and domain model
Deliverables: Maven build, Spring Boot entry point, configuration properties, domain
types and wire-protocol messages.
Files: `pom.xml`, `src/main/java/.../TalkBurstApplication.java`,
`config/TalkBurstProperties.java`, `domain/*` (`Role`, `Participant`, `TalkSession`,
`FloorState`, `FloorGrant`), `protocol/*` (`ClientMessage`, `ServerMessage`,
`MessageType`), `src/main/resources/application.yml`, `.env.example`.

### Phase 2 — Redis-backed session management + REST API
Deliverables: Redis configuration, `SessionRepository` (create/get/delete session,
add/remove members, TTL refresh), `SessionService` with validation, REST controller
to create/list/inspect sessions, error handling.
Files: `config/RedisConfig.java`, `session/SessionRepository.java`,
`session/SessionService.java`, `api/SessionController.java`, `api/dto/*`,
`api/ApiExceptionHandler.java`, `session/SessionNotFoundException.java`.

### Phase 3 — Floor control engine
Deliverables: atomic acquire/release/renew via Lua, priority queue, pre-emption,
expiry handling via a scheduled sweeper, floor events.
Files: `floor/FloorControlService.java`, `floor/FloorDecision.java`,
`floor/FloorEvent.java`, `floor/RedisFloorStore.java`,
`src/main/resources/lua/*.lua`, `floor/FloorExpiryScheduler.java`.

### Phase 4 — WebSocket signalling, audio relay and cross-instance fan-out
Deliverables: WebSocket config, `PttWebSocketHandler` (text control + binary audio),
local connection registry, Redis pub/sub `EventBus` for multi-instance relay,
handshake interceptor for session/user/role query params, minimal browser demo client.
Files: `ws/WebSocketConfig.java`, `ws/PttWebSocketHandler.java`,
`ws/ConnectionRegistry.java`, `ws/HandshakeInterceptor.java`,
`events/EventBus.java`, `events/RedisEventBus.java`, `events/SessionEnvelope.java`,
`src/main/resources/static/index.html`.

### Phase 5 — Tests (unit + multi-client integration)
Deliverables: unit tests for priority scoring and protocol codec; Testcontainers
Redis integration tests for floor control under concurrency (N clients racing for
the floor, exactly one wins), pre-emption, expiry; end-to-end WebSocket tests with
several `StandardWebSocketClient`s verifying grant/deny/queue/relay behaviour.
Files: `src/test/java/...` (`FloorControlServiceIT`, `PttWebSocketIT`,
`RolePriorityTest`, `ProtocolCodecTest`, `support/RedisTestBase`, `support/TestClient`).

After phase 5: `.gitignore`, `README.md` with architecture diagram, run and test docs.
