package com.example.speech.service;

import com.example.speech.config.SpeechProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Validates the shared token from {@code Authorization: Bearer} or {@code ?token=}. */
@Service
public class TokenAuthService {

    private static final String BEARER = "Bearer ";

    private final SpeechProperties properties;

    public TokenAuthService(SpeechProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthorized(ServerHttpRequest request) {
        if (!properties.authEnabled()) {
            return true;
        }
        String presented = presentedToken(request);
        return presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.authToken().getBytes(StandardCharsets.UTF_8));
    }

    private static String presentedToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        return token == null ? null : UriUtils.decode(token, StandardCharsets.UTF_8);
    }
}

