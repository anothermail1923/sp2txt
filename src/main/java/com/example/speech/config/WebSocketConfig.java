package com.example.speech.config;

import com.example.speech.controller.SpeechWebSocketHandler;
import com.example.speech.interceptor.TokenHandshakeInterceptor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String ENDPOINT = "/ws/speech";

    private final SpeechWebSocketHandler handler;
    private final TokenHandshakeInterceptor tokenInterceptor;
    private final SpeechProperties properties;

    public WebSocketConfig(
            SpeechWebSocketHandler handler,
            TokenHandshakeInterceptor tokenInterceptor,
            SpeechProperties properties) {
        this.handler = handler;
        this.tokenInterceptor = tokenInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, ENDPOINT)
                .addInterceptors(tokenInterceptor)
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

