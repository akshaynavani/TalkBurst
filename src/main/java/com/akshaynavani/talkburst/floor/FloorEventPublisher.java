package com.akshaynavani.talkburst.floor;

/** Fans floor events out to every node serving the session. */
public interface FloorEventPublisher {
    void publish(FloorEvent event);
}
