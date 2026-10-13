package com.example.speech.service;

import com.example.speech.config.SpeechProperties;
import com.example.speech.config.WebSocketConfig;
import com.example.speech.dto.AudioFormat;
import com.example.speech.dto.StatusResponse;
import com.example.speech.recognizer.RecognizerFactory;

import org.springframework.stereotype.Service;

@Service
public class StatusService {

    private final RecognizerFactory recognizerFactory;
    private final SessionService sessionService;
    private final SpeechProperties properties;

    public StatusService(
            RecognizerFactory recognizerFactory,
            SessionService sessionService,
            SpeechProperties properties) {
        this.recognizerFactory = recognizerFactory;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    public StatusResponse getStatus() {
        return new StatusResponse(
                "UP",
                recognizerFactory.engine(),
                recognizerFactory.model(),
                WebSocketConfig.ENDPOINT,
                new AudioFormat("pcm_s16le", (int) recognizerFactory.sampleRate(), 1),
                properties.maxBinaryMessageBytes(),
                sessionService.activeSessions(),
                properties.maxSessions(),
                properties.authEnabled());
    }
}

