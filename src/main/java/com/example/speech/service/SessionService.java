package com.example.speech.service;

import com.example.speech.config.SpeechProperties;
import com.example.speech.model.SpeechSession;
import com.example.speech.repository.SessionRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/** Tracks active sessions and enforces the maximum session limit. */
@Service
public class SessionService {

    private final SessionRepository repository;
    private final Semaphore permits;

    public SessionService(SessionRepository repository, SpeechProperties properties) {
        this.repository = repository;
        this.permits = new Semaphore(properties.maxSessions());
    }

    /** Registers the session; returns false when the limit is reached. */
    public boolean register(String id, SpeechSession session) {
        if (!permits.tryAcquire()) {
            return false;
        }
        repository.save(id, session);
        return true;
    }

    public Optional<SpeechSession> find(String id) {
        return repository.findById(id);
    }

    /** Removes the session and frees its permit. */
    public Optional<SpeechSession> unregister(String id) {
        Optional<SpeechSession> removed = repository.remove(id);
        removed.ifPresent(s -> permits.release());
        return removed;
    }

    public List<SpeechSession> all() {
        return new ArrayList<>(repository.findAll());
    }

    public int activeSessions() {
        return repository.count();
    }
}

