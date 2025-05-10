package com.akshaynavani.talkburst.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.akshaynavani.talkburst.config.TalkBurstProperties;
import com.akshaynavani.talkburst.domain.Participant;
import com.akshaynavani.talkburst.domain.TalkSession;

@Service
public class SessionService {

    private final SessionRepository repository;
    private final TalkBurstProperties props;

    public SessionService(SessionRepository repository, TalkBurstProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public TalkSession create(String name, String createdBy, Integer maxParticipants) {
        int cap = props.session().maxParticipants();
        int max = maxParticipants == null ? cap : Math.min(Math.max(maxParticipants, 2), cap);
        TalkSession session = new TalkSession(
                UUID.randomUUID().toString().substring(0, 8),
                name, createdBy, Instant.now(), max);
        repository.save(session, props.session().idleTtl());
        return session;
    }

    public TalkSession get(String sessionId) {
        return repository.findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    public List<TalkSession> list() {
        return repository.findAll();
    }

    public void close(String sessionId) {
        get(sessionId);
        repository.delete(sessionId);
    }

    /** Adds the participant to the session. Returns true if this is a fresh join (not a reconnect). */
    public boolean join(String sessionId, Participant participant) {
        TalkSession session = get(sessionId);
        return switch (repository.addMember(sessionId, participant, session.maxParticipants(),
                props.session().idleTtl())) {
            case JOINED -> true;
            case REJOINED -> false;
            case FULL -> throw new SessionFullException(sessionId);
            case NO_SUCH_SESSION -> throw new SessionNotFoundException(sessionId);
        };
    }

    public boolean leave(String sessionId, String userId) {
        return repository.removeMember(sessionId, userId);
    }

    public List<Participant> members(String sessionId) {
        return repository.members(sessionId);
    }

    public void touch(String sessionId) {
        repository.touch(sessionId, props.session().idleTtl());
    }
}
