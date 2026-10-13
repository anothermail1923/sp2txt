package com.example.speech;

import com.example.speech.recognizer.RecognizerFactory;
import com.example.speech.recognizer.StreamingRecognizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Streams PCM audio from each WebSocket client into its own recognizer and
 * sends back {@code ready}, {@code partial}, {@code final}, {@code stopped}
 * and {@code error} events as JSON.
 *
 * <p>The model is shared; recognizers are per session and only ever touched
 * while holding that session's lock, so audio, control commands, disconnects
 * and shutdown cannot use a recognizer concurrently or after it is closed.
 */
@Component
public class SpeechWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log =
            LoggerFactory.getLogger(SpeechWebSocketHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    static final CloseStatus SESSION_LIMIT_REACHED =
            CloseStatus.POLICY_VIOLATION.withReason("Server busy");

    private final RecognizerFactory recognizerFactory;
    private final ObjectMapper mapper;
    private final Semaphore sessionPermits;
    private final Map<String, SpeechSession> sessions = new ConcurrentHashMap<>();

    public SpeechWebSocketHandler(
            RecognizerFactory recognizerFactory,
            SpeechProperties properties,
            ObjectMapper mapper) {
        this.recognizerFactory = recognizerFactory;
        this.mapper = mapper;
        this.sessionPermits = new Semaphore(properties.maxSessions());
    }

    /** Number of currently connected clients. */
    public int activeSessions() {
        return sessions.size();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!sessionPermits.tryAcquire()) {
            log.warn("Rejecting session {}: session limit reached", session.getId());
            sendJson(session, event("error", "Server busy, try again later"));
            // 1008 rather than 1013 (try again later): some clients, e.g.
            // Tomcat's, treat 1013 as a protocol error.
            session.close(SESSION_LIMIT_REACHED);
            return;
        }

        SpeechSession speech = new SpeechSession(
                new ConcurrentWebSocketSessionDecorator(
                        session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        sessions.put(session.getId(), speech);
        log.debug("Session {} connected from {}", session.getId(), session.getRemoteAddress());

        start(speech);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SpeechSession speech = sessions.get(session.getId());
        if (speech == null) {
            return;
        }

        ByteBuffer payload = message.getPayload();
        int length = payload.remaining();
        if (length == 0) {
            return;
        }
        if (length % 2 != 0) {
            sendJson(speech.out, event("error",
                    "Audio must be 16-bit PCM; chunk length must be even"));
            return;
        }

        byte[] audio = new byte[length];
        payload.get(audio);

        synchronized (speech) {
            StreamingRecognizer recognizer = speech.recognizer;
            if (recognizer == null) {
                sendJson(speech.out, event("error",
                        "Recognition is not active; send START first"));
                return;
            }

            try {
                if (recognizer.acceptAudio(audio, audio.length)) {
                    String text = recognizer.result();
                    if (!text.isBlank()) {
                        sendJson(speech.out, event("final", text));
                    } else if (!speech.lastPartial.isEmpty()) {
                        // Phrase ended with no words: clear the stale partial.
                        sendJson(speech.out, event("partial", ""));
                    }
                    speech.lastPartial = "";
                } else {
                    String partial = recognizer.partialResult();
                    if (!partial.equals(speech.lastPartial)) {
                        speech.lastPartial = partial;
                        sendJson(speech.out, event("partial", partial));
                    }
                }
            } catch (RuntimeException e) {
                log.error("Recognition failed for session {}", session.getId(), e);
                sendJson(speech.out, event("error", "Recognition failed"));
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SpeechSession speech = sessions.get(session.getId());
        if (speech == null) {
            return;
        }

        switch (command(message.getPayload())) {
            case "STOP" -> stop(speech);
            case "START" -> start(speech);
            default -> sendJson(speech.out, event("error",
                    "Unknown command; expected START or STOP"));
        }
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
        SpeechSession speech = sessions.remove(session.getId());
        if (speech == null) {
            return;
        }
        release(speech);
        sessionPermits.release();
        log.debug("Session {} closed: {}", session.getId(), status);
    }

    /** Closes all client connections and recognizers before the model is unloaded. */
    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(speech -> {
            release(speech);
            try {
                speech.out.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.debug("Failed to close session {}", speech.out.getId(), e);
            }
        });
    }

    private void start(SpeechSession speech) {
        synchronized (speech) {
            if (speech.recognizer != null) {
                sendJson(speech.out, event("error", "Recognition already active"));
                return;
            }

            try {
                speech.recognizer = recognizerFactory.create();
            } catch (Exception e) {
                log.error("Could not create recognizer for session {}", speech.out.getId(), e);
                sendJson(speech.out, event("error", "Could not start recognition"));
                closeQuietly(speech.out, CloseStatus.SERVER_ERROR);
                return;
            }

            speech.lastPartial = "";
            ObjectNode ready = event("ready", "");
            ready.put("sampleRate", (int) recognizerFactory.sampleRate());
            ready.put("model", recognizerFactory.model());
            sendJson(speech.out, ready);
        }
    }

    private void stop(SpeechSession speech) {
        synchronized (speech) {
            StreamingRecognizer recognizer = speech.recognizer;
            if (recognizer != null) {
                try {
                    String text = recognizer.finalResult();
                    if (!text.isBlank()) {
                        sendJson(speech.out, event("final", text));
                    }
                } catch (RuntimeException e) {
                    log.error("Final recognition failed for session {}", speech.out.getId(), e);
                    sendJson(speech.out, event("error", "Recognition failed"));
                } finally {
                    release(speech);
                }
            }
            sendJson(speech.out, event("stopped", ""));
        }
    }

    private void release(SpeechSession speech) {
        synchronized (speech) {
            if (speech.recognizer != null) {
                speech.recognizer.close();
                speech.recognizer = null;
            }
        }
    }

    /** Accepts {@code STOP} / {@code START}, or JSON such as {"type":"stop"}. */
    private String command(String payload) {
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode json = mapper.readTree(trimmed);
                trimmed = json.path("type").asText("");
            } catch (IOException e) {
                return "";
            }
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private ObjectNode event(String type, String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("text", text);
        return node;
    }

    private void sendJson(WebSocketSession session, ObjectNode event) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(event.toString()));
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not send to session {}: {}", session.getId(), e.toString());
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close session {}", session.getId(), e);
        }
    }

    /** Per-connection state; all fields are guarded by the instance lock. */
    private static final class SpeechSession {

        private final WebSocketSession out;
        private StreamingRecognizer recognizer;
        private String lastPartial = "";

        private SpeechSession(WebSocketSession out) {
            this.out = out;
        }
    }
}
