package com.example.speech.repository;

import com.example.speech.model.SpeechSession;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory store of active speech sessions, keyed by WebSocket session id. */
@Repository
public class SessionRepository {

    private final Map<String, SpeechSession> sessions = new ConcurrentHashMap<>();

    public void save(String id, SpeechSession session) {
        sessions.put(id, session);
    }

    public Optional<SpeechSession> findById(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Optional<SpeechSession> remove(String id) {
        return Optional.ofNullable(sessions.remove(id));
    }

    public Collection<SpeechSession> findAll() {
        return sessions.values();
    }

    public int count() {
        return sessions.size();
    }
}

