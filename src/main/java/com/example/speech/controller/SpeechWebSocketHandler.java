package com.example.speech.controller;

import com.example.speech.model.SpeechSession;
import com.example.speech.service.SessionService;
import com.example.speech.service.SpeechRecognitionService;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;

/** WebSocket entry point; delegates to the session and recognition services. */
@Component
public class SpeechWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SpeechWebSocketHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    public static final CloseStatus SESSION_LIMIT_REACHED =
            CloseStatus.POLICY_VIOLATION.withReason("Server busy");

    private final SessionService sessionService;
    private final SpeechRecognitionService recognitionService;

    public SpeechWebSocketHandler(SessionService sessionService, SpeechRecognitionService recognitionService) {
        this.sessionService = sessionService;
        this.recognitionService = recognitionService;
    }

    /** Number of currently connected clients. */
    public int activeSessions() {
        return sessionService.activeSessions();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        SpeechSession speech = new SpeechSession(
                new ConcurrentWebSocketSessionDecorator(
                        session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));

        if (!sessionService.register(session.getId(), speech)) {
            log.warn("Rejecting session {}: session limit reached", session.getId());
            recognitionService.sendError(session, "Server busy, try again later");
            // 1008 rather than 1013: some clients treat 1013 as a protocol error.
            session.close(SESSION_LIMIT_REACHED);
            return;
        }
        log.debug("Session {} connected from {}", session.getId(), session.getRemoteAddress());
        recognitionService.start(speech);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        sessionService.find(session.getId()).ifPresent(
                speech -> recognitionService.processAudio(speech, message.getPayload()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessionService.find(session.getId()).ifPresent(speech -> {
            switch (recognitionService.parseCommand(message.getPayload())) {
                case "STOP" -> recognitionService.stop(speech);
                case "START" -> recognitionService.start(speech);
                default -> recognitionService.sendError(speech,
                        "Unknown command; expected START or STOP");
            }
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception)
            throws Exception {
        log.debug("Transport error on session {}: {}", session.getId(), exception.toString());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionService.unregister(session.getId()).ifPresent(speech -> {
            recognitionService.release(speech);
            log.debug("Session {} closed: {}", session.getId(), status);
        });
    }

    /** Closes all client connections and recognizers before the model is unloaded. */
    @PreDestroy
    public void shutdown() {
        sessionService.all().forEach(speech -> {
            recognitionService.release(speech);
            try {
                speech.getOut().close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.debug("Failed to close session {}", speech.getOut().getId(), e);
            }
        });
    }
}

