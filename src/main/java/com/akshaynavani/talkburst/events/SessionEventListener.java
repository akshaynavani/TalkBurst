package com.akshaynavani.talkburst.events;

import com.akshaynavani.talkburst.floor.FloorEvent;

/** Receives every event for sessions this node has subscribed to (including its own). */
public interface SessionEventListener {

    void onFloorEvent(FloorEvent event);

    void onParticipantChange(String sessionId, SessionEnvelope.ParticipantChange change);

    void onAudio(String sessionId, String senderUserId, byte[] frame);
}
