package com.example.speech;

import com.example.speech.recognizer.RecognizerFactory;
import com.example.speech.recognizer.VoskRecognizerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class SpeechConfig {

    /**
     * Loads the Vosk model once at startup. The model is shared by every session;
     * each session gets its own recognizer from this factory. The model is closed
     * on shutdown, after the WebSocket handler (which depends on it) has released
     * all recognizers.
     */
    @Bean(destroyMethod = "close")
    public RecognizerFactory recognizerFactory(
            SpeechProperties properties,
            ObjectMapper mapper) throws IOException {

        return new VoskRecognizerFactory(
                Path.of(properties.modelPath()),
                properties.sampleRate(),
                mapper);
    }
}
