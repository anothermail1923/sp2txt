package com.example.speech.service;

import com.example.speech.model.SpeechSession;
import com.example.speech.recognizer.RecognizerFactory;
import com.example.speech.recognizer.StreamingRecognizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Recognition logic: recognizer lifecycle, audio processing and JSON events.
 * A recognizer is only touched while holding its session's lock.
 */
@Service
public class SpeechRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecognitionService.class);

    private final RecognizerFactory recognizerFactory;
    private final ObjectMapper mapper;

    public SpeechRecognitionService(RecognizerFactory recognizerFactory, ObjectMapper mapper) {
        this.recognizerFactory = recognizerFactory;
        this.mapper = mapper;
    }

    public void start(SpeechSession speech) {
        synchronized (speech) {
            if (speech.getRecognizer() != null) {
                sendError(speech, "Recognition already active");
                return;
            }
            try {
                speech.setRecognizer(recognizerFactory.create());
            } catch (Exception e) {
                log.error("Could not create recognizer for session {}", speech.getOut().getId(), e);
                sendError(speech, "Could not start recognition");
                closeQuietly(speech.getOut());
                return;
            }
            speech.setLastPartial("");
            ObjectNode ready = event("ready", "");
            ready.put("sampleRate", (int) recognizerFactory.sampleRate());
            ready.put("model", recognizerFactory.model());
            sendJson(speech.getOut(), ready);
        }
    }

    public void stop(SpeechSession speech) {
        synchronized (speech) {
            StreamingRecognizer recognizer = speech.getRecognizer();
            if (recognizer != null) {
                try {
                    String text = recognizer.finalResult();
                    if (!text.isBlank()) {
                        sendJson(speech.getOut(), event("final", text));
                    }
                } catch (RuntimeException e) {
                    log.error("Final recognition failed for session {}", speech.getOut().getId(), e);
                    sendError(speech, "Recognition failed");
                } finally {
                    release(speech);
                }
            }
            sendJson(speech.getOut(), event("stopped", ""));
        }
    }

    public void processAudio(SpeechSession speech, ByteBuffer payload) {
        int length = payload.remaining();
        if (length == 0) {
            return;
        }
        if (length % 2 != 0) {
            sendError(speech, "Audio must be 16-bit PCM; chunk length must be even");
            return;
        }
        byte[] audio = new byte[length];
        payload.get(audio);

        synchronized (speech) {
            StreamingRecognizer recognizer = speech.getRecognizer();
            if (recognizer == null) {
                sendError(speech, "Recognition is not active; send START first");
                return;
            }
            try {
                if (recognizer.acceptAudio(audio, audio.length)) {
                    String text = recognizer.result();
                    if (!text.isBlank()) {
                        sendJson(speech.getOut(), event("final", text));
                    } else if (!speech.getLastPartial().isEmpty()) {
                        sendJson(speech.getOut(), event("partial", ""));
                    }
                    speech.setLastPartial("");
                } else {
                    String partial = recognizer.partialResult();
                    if (!partial.equals(speech.getLastPartial())) {
                        speech.setLastPartial(partial);
                        sendJson(speech.getOut(), event("partial", partial));
                    }
                }
            } catch (RuntimeException e) {
                log.error("Recognition failed for session {}", speech.getOut().getId(), e);
                sendError(speech, "Recognition failed");
            }
        }
    }

    public void release(SpeechSession speech) {
        synchronized (speech) {
            if (speech.getRecognizer() != null) {
                speech.getRecognizer().close();
                speech.setRecognizer(null);
            }
        }
    }

    /** Accepts {@code STOP} / {@code START}, or JSON such as {"type":"stop"}. */
    public String parseCommand(String payload) {
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

    public void sendError(SpeechSession speech, String message) {
        sendJson(speech.getOut(), event("error", message));
    }

    public void sendError(WebSocketSession session, String message) {
        sendJson(session, event("error", message));
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

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException e) {
            log.debug("Failed to close session {}", session.getId(), e);
        }
    }
}

