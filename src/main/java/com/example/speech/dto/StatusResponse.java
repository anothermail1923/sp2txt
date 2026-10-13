package com.example.speech.dto;

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

