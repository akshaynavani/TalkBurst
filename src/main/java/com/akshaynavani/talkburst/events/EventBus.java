package com.akshaynavani.talkburst.events;

import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.floor.FloorEventPublisher;

/**
 * Cross-node fan-out for a session. A node subscribes to a session while it has at
 * least one local connection in it, and publishes everything through the bus (even
 * events for its own sockets) so delivery order is identical on every node.
 */
public interface EventBus extends FloorEventPublisher {

    void publishParticipant(String sessionId, SessionEnvelope.ParticipantChange.Change change, Participant p);

    void publishAudio(String sessionId, String senderUserId, byte[] frame);

    void subscribe(String sessionId);

    void unsubscribe(String sessionId);

    void setListener(SessionEventListener listener);
}
