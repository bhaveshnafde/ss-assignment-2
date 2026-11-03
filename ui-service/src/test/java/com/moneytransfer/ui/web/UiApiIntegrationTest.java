package com.moneytransfer.ui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole UI service on a random port, with a fake "account" service behind it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiApiIntegrationTest {

    private static final HttpServer FAKE_ACCOUNT = startFake();

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("ui.services.account", () -> "http://127.0.0.1:" + FAKE_ACCOUNT.getAddress().getPort());
        registry.add("ui.services.ledger", () -> "http://127.0.0.1:1");   // nothing listens on port 1
    }

    @AfterAll
    static void stopFake() {
        FAKE_ACCOUNT.stop(0);
    }

    @Autowired TestRestTemplate http;

    @Test
    void thePageIsServedAtTheRoot() {
        ResponseEntity<String> page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Money Transfer").contains("app.js");
    }

    @Test
    void aCallIsForwardedWithItsBodyAndKey_andTheAnswerComesBackUnchanged() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "ui-test-1");

        ResponseEntity<JsonNode> r = http.postForEntity("/proxy/account/api/v1/echo?x=1",
                new HttpEntity<>("{\"amount\":5}", headers), JsonNode.class);

        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(r.getBody().get("path").asText()).isEqualTo("/api/v1/echo?x=1");
        assertThat(r.getBody().get("key").asText()).isEqualTo("ui-test-1");
        assertThat(r.getBody().get("body").asText()).isEqualTo("{\"amount\":5}");
    }

    @Test
    void aServiceThatIsNotRunningIs502_andAnUnknownServiceIs404() {
        ResponseEntity<JsonNode> down = http.getForEntity("/proxy/ledger/health", JsonNode.class);
        assertThat(down.getStatusCode().value()).isEqualTo(502);
        assertThat(down.getBody().at("/error/code").asText()).isEqualTo("SERVICE_UNREACHABLE");

        ResponseEntity<JsonNode> unknown = http.getForEntity("/proxy/nope/health", JsonNode.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().at("/error/code").asText()).isEqualTo("UNKNOWN_SERVICE");
    }

    @Test
    void aBurstFiresEveryCopy_andReturnsTheAnswersInOrder() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String request = "{\"service\":\"account\",\"method\":\"POST\",\"path\":\"/api/v1/echo\","
                + "\"headers\":{\"Idempotency-Key\":\"same\"},\"bodies\":[{\"n\":1},{\"n\":2},{\"n\":3}]}";

        JsonNode answers = http.postForObject("/api/ui/burst", new HttpEntity<>(request, headers), JsonNode.class);

        assertThat(answers).hasSize(3);
        assertThat(answers.get(0).get("status").asInt()).isEqualTo(201);
        assertThat(answers.get(0).at("/body/body").asText()).isEqualTo("{\"n\":1}");
        assertThat(answers.get(2).at("/body/body").asText()).isEqualTo("{\"n\":3}");
        assertThat(answers.get(1).at("/body/key").asText()).isEqualTo("same");
    }

    @Test
    void servicesListAndHealth() {
        JsonNode services = http.getForObject("/api/ui/services", JsonNode.class);
        assertThat(services).hasSize(5);
        assertThat(http.getForObject("/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }

    /** Answers 201 with what it received, so the test can check what was forwarded. */
    private static HttpServer startFake() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                String json = "{\"path\":\"" + exchange.getRequestURI() + "\",\"key\":\"" + key
                        + "\",\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Idempotent-Replayed", "false");
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
