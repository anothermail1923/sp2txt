package com.example.speech;

import com.example.speech.controller.SpeechWebSocketHandler;
import com.example.speech.recognizer.RecognizerFactory;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "speech.max-sessions=2",
                "speech.auth-token=secret token",
                "spring.main.allow-bean-definition-overriding=true"
        })
class SpeechWebSocketIntegrationTest {

    private static final byte[] CHUNK = new byte[3200]; // 100 ms of 16 kHz PCM16

    @TestConfiguration
    static class FakeEngine {
        @Bean
        @Primary
        RecognizerFactory recognizerFactory() {
            return new FakeRecognizerFactory();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    RecognizerFactory recognizerFactory;

    @Autowired
    SpeechWebSocketHandler handler;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Client> clients = new ArrayList<>();

    @AfterEach
    void closeClients() throws Exception {
        for (Client client : clients) {
            if (client.session.isOpen()) {
                client.session.close();
            }
        }
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.activeSessions() == 0);
    }

    @Test
    void streamsPartialAndFinalTranscripts() throws Exception {
        Client client = connect("secret token");
        JsonNode ready = client.next();
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("sampleRate").asInt()).isEqualTo(16000);

        client.sendAudio(CHUNK);
        assertEvent(client.next(), "partial", "hello");
        client.sendAudio(CHUNK);
        assertEvent(client.next(), "partial", "hello world");
        client.sendAudio(CHUNK);
        assertEvent(client.next(), "final", "hello world");

        client.sendAudio(CHUNK);
        assertEvent(client.next(), "partial", "hello");

        client.session.sendMessage(new TextMessage("STOP"));
        assertEvent(client.next(), "final", "goodbye");
        assertEvent(client.next(), "stopped", "");

        FakeRecognizerFactory fake = (FakeRecognizerFactory) recognizerFactory;
        assertThat(fake.created.get(fake.created.size() - 1).closed).isTrue();
    }

    @Test
    void canRestartAfterStopAndAcceptsJsonCommands() throws Exception {
        Client client = connect("secret token");
        assertThat(client.next().path("type").asText()).isEqualTo("ready");

        client.session.sendMessage(new TextMessage("{\"type\":\"stop\"}"));
        assertEvent(client.next(), "stopped", "");

        client.sendAudio(CHUNK);
        assertThat(client.next().path("type").asText()).isEqualTo("error");

        client.session.sendMessage(new TextMessage("START"));
        assertThat(client.next().path("type").asText()).isEqualTo("ready");
        client.sendAudio(CHUNK);
        assertEvent(client.next(), "partial", "hello");
    }

    @Test
    void rejectsOddLengthAudio() throws Exception {
        Client client = connect("secret token");
        client.next();

        client.sendAudio(new byte[3]);
        assertThat(client.next().path("type").asText()).isEqualTo("error");
    }

    @Test
    void releasesRecognizerWhenClientDisconnects() throws Exception {
        Client client = connect("secret token");
        client.next();
        FakeRecognizerFactory fake = (FakeRecognizerFactory) recognizerFactory;
        FakeRecognizerFactory.FakeRecognizer recognizer =
                fake.created.get(fake.created.size() - 1);

        client.session.close();

        await().atMost(5, TimeUnit.SECONDS).until(() -> recognizer.closed);
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.activeSessions() == 0);
    }

    @Test
    void enforcesSessionLimit() throws Exception {
        connect("secret token").next();
        connect("secret token").next();

        Client third = connect("secret token");
        assertThat(third.next().path("type").asText()).isEqualTo("error");
        CloseStatus status = third.closed.get(5, TimeUnit.SECONDS);
        assertThat(status).isEqualTo(SpeechWebSocketHandler.SESSION_LIMIT_REACHED);
    }

    @Test
    void rejectsHandshakeWithoutToken() {
        assertThatThrownBy(() -> connect(null))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> connect("wrong"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void acceptsTokenAsQueryParameter() throws Exception {
        Client client = connect(null, "?token=secret%20token");
        assertThat(client.next().path("type").asText()).isEqualTo("ready");
    }

    @Test
    void reportsStatus() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/api/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");

        JsonNode status = rest.getForObject("/api/status", JsonNode.class);
        assertThat(status.path("engine").asText()).isEqualTo("fake");
        assertThat(status.path("endpoint").asText()).isEqualTo("/ws/speech");
        assertThat(status.path("audio").path("sampleRate").asInt()).isEqualTo(16000);
        assertThat(status.path("maxSessions").asInt()).isEqualTo(2);
        assertThat(status.path("authRequired").asBoolean()).isTrue();
    }

    private void assertEvent(JsonNode event, String type, String text) {
        assertThat(event.path("type").asText()).isEqualTo(type);
        assertThat(event.path("text").asText()).isEqualTo(text);
    }

    private Client connect(String bearerToken) throws Exception {
        return connect(bearerToken, "");
    }

    private Client connect(String bearerToken, String query) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        Client client = new Client();
        client.session = new StandardWebSocketClient()
                .execute(client, headers,
                        URI.create("ws://localhost:" + port + "/ws/speech" + query))
                .get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    private final class Client extends TextWebSocketHandler {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        JsonNode next() throws Exception {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("expected a message from the server").isNotNull();
            return mapper.readTree(message);
        }

        void sendAudio(byte[] audio) throws Exception {
            session.sendMessage(new BinaryMessage(audio));
        }
    }
}
