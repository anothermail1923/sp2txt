package com.example.speech.model;

import com.example.speech.recognizer.StreamingRecognizer;

import org.springframework.web.socket.WebSocketSession;

/** Per-connection state; callers must synchronize on the instance when mutating it. */
public class SpeechSession {

    private final WebSocketSession out;
    private StreamingRecognizer recognizer;
    private String lastPartial = "";

    public SpeechSession(WebSocketSession out) {
        this.out = out;
    }

    public WebSocketSession getOut() {
        return out;
    }

    public StreamingRecognizer getRecognizer() {
        return recognizer;
    }

    public void setRecognizer(StreamingRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    public String getLastPartial() {
        return lastPartial;
    }

    public void setLastPartial(String lastPartial) {
        this.lastPartial = lastPartial;
    }
}

