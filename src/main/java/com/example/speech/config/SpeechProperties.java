package com.example.speech.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Settings for the speech service, bound from the {@code speech.*} properties.
 *
 * @param modelPath             directory of the extracted Vosk model
 * @param sampleRate            sample rate (Hz) of the mono signed 16-bit PCM audio
 * @param maxSessions           maximum number of simultaneous WebSocket sessions
 * @param maxBinaryMessageBytes largest accepted audio chunk
 * @param idleTimeout           sessions with no traffic for this long are closed
 * @param allowedOriginPatterns browser origins allowed to connect
 * @param authToken             optional shared token; blank disables the check
 */
@Validated
@ConfigurationProperties("speech")
public record SpeechProperties(
        @DefaultValue("models/vosk-model-small-en-us-0.15") @NotBlank String modelPath,
        @DefaultValue("16000") @Positive float sampleRate,
        @DefaultValue("20") @Min(1) int maxSessions,
        @DefaultValue("65536") @Min(1024) int maxBinaryMessageBytes,
        @DefaultValue("60s") Duration idleTimeout,
        @DefaultValue List<String> allowedOriginPatterns,
        @DefaultValue("") String authToken) {

    public boolean authEnabled() {
        return authToken != null && !authToken.isBlank();
    }
}

