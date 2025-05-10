package com.akshaynavani.talkburst.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshaynavani.talkburst.api.dto.CreateSessionRequest;
import com.akshaynavani.talkburst.api.dto.SessionResponse;
import com.akshaynavani.talkburst.session.SessionService;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody CreateSessionRequest req) {
        var s = sessions.create(req.name(), req.createdBy(), req.maxParticipants());
        return SessionResponse.of(s, List.of());
    }

    @GetMapping
    public List<SessionResponse> list() {
        return sessions.list().stream().map(s -> SessionResponse.of(s, null)).toList();
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable String id) {
        return SessionResponse.of(sessions.get(id), sessions.members(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String id) {
        sessions.close(id);
    }
}
