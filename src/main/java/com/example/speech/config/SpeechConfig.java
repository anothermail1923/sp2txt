package com.example.speech.config;

import com.example.speech.recognizer.RecognizerFactory;
import com.example.speech.recognizer.VoskRecognizerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class SpeechConfig {

    /** Loads the Vosk model once at startup; each session gets its own recognizer. */
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

