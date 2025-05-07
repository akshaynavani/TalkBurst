package com.akshaynavani.talkburst.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound control message.
 *
 * @param type      what the client wants
 * @param requestId optional client correlation id, echoed back in replies
 * @param token     floor token, required for FLOOR_RELEASE / FLOOR_RENEW
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientMessage(ClientMessageType type, String requestId, String token) {
}
