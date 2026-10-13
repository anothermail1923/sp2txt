package com.example.speech;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Rejects the WebSocket handshake unless the client presents the configured
 * token, either as {@code Authorization: Bearer <token>} or as a
 * {@code ?token=<token>} query parameter. Does nothing when no token is set.
 */
class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final String BEARER = "Bearer ";

    private final SpeechProperties properties;

    TokenHandshakeInterceptor(SpeechProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        if (!properties.authEnabled()) {
            return true;
        }

        if (matches(presentedToken(request))) {
            return true;
        }

        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
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

    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.authToken().getBytes(StandardCharsets.UTF_8));
    }
}
