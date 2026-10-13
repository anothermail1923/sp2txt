package com.example.speech.web;

import com.example.speech.SpeechProperties;
import com.example.speech.SpeechWebSocketConfig;
import com.example.speech.SpeechWebSocketHandler;
import com.example.speech.recognizer.RecognizerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Health and status endpoints for monitoring and client discovery. */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final RecognizerFactory recognizerFactory;
    private final SpeechWebSocketHandler handler;
    private final SpeechProperties properties;

    public StatusController(
            RecognizerFactory recognizerFactory,
            SpeechWebSocketHandler handler,
            SpeechProperties properties) {
        this.recognizerFactory = recognizerFactory;
        this.handler = handler;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(
                "UP",
                recognizerFactory.engine(),
                recognizerFactory.model(),
                SpeechWebSocketConfig.ENDPOINT,
                new AudioFormat("pcm_s16le", (int) recognizerFactory.sampleRate(), 1),
                properties.maxBinaryMessageBytes(),
                handler.activeSessions(),
                properties.maxSessions(),
                properties.authEnabled());
    }

    public record StatusResponse(
            String status,
            String engine,
            String model,
            String endpoint,
            AudioFormat audio,
            int maxChunkBytes,
            int activeSessions,
            int maxSessions,
            boolean authRequired) {
    }

    public record AudioFormat(String encoding, int sampleRate, int channels) {
    }
}
