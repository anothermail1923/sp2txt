package com.example.speech;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class SpeechWebSocketConfig implements WebSocketConfigurer {

    public static final String ENDPOINT = "/ws/speech";

    private final SpeechWebSocketHandler handler;
    private final SpeechProperties properties;

    public SpeechWebSocketConfig(
            SpeechWebSocketHandler handler,
            SpeechProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Native mobile apps normally send no Origin header and are not affected
        // by this list; it only restricts which browser pages may connect.
        registry.addHandler(handler, ENDPOINT)
                .addInterceptors(new TokenHandshakeInterceptor(properties))
                .setAllowedOriginPatterns(
                        properties.allowedOriginPatterns().toArray(String[]::new));
    }

    /** Limits audio chunk size and idle time at the container level. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container =
                new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(properties.maxBinaryMessageBytes());
        container.setMaxTextMessageBufferSize(8 * 1024);
        container.setMaxSessionIdleTimeout(properties.idleTimeout().toMillis());
        return container;
    }
}
