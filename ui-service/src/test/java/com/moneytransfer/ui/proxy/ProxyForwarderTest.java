package com.moneytransfer.ui.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forwarder against a tiny fake service (the JDK's built-in HTTP server), no Spring needed. */
class ProxyForwarderTest {

    private HttpServer fake;
    private ProxyForwarder forwarder;
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenUri = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenCookie = new AtomicReference<>();

    @BeforeEach
    void startFakeService() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenUri.set(exchange.getRequestURI().toString());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/slow")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = path.equals("/refuse") ? 409 : 201;
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
            exchange.getResponseHeaders().add("Set-Cookie", "secret=1");
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        fake.start();
        URI base = URI.create("http://127.0.0.1:" + fake.getAddress().getPort());
        forwarder = new ProxyForwarder(Map.of("account", base, "ledger", URI.create("http://127.0.0.1:" + freePort())),
                Duration.ofMillis(500), Duration.ofMillis(800));
    }

    @AfterEach
    void stopFakeService() {
        fake.stop(0);
    }

    @Test
    void forwardsMethodPathQueryBodyAndTheDemoHeaders() {
        ProxyForwarder.Result r = forwarder.forward("account", "POST", "/api/v1/holds", "x=1&y=2",
                Map.of("Content-Type", "application/json", "Idempotency-Key", "k-1", "Cookie", "session=abc"),
                "{\"amount\":100}".getBytes(StandardCharsets.UTF_8));

        assertEquals(201, r.status());
        assertEquals("POST", seenMethod.get());
        assertEquals("/api/v1/holds?x=1&y=2", seenUri.get());
        assertEquals("{\"amount\":100}", seenBody.get());
        assertEquals("k-1", seenKey.get());
        assertNull(seenCookie.get(), "browser cookies are not passed on");
        assertEquals("{\"ok\":true}", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("true", r.headers().get("Idempotent-Replayed"));
        assertFalse(r.headers().containsKey("Set-Cookie"), "only the listed response headers come back");
    }

    @Test
    void aRefusalFromTheServiceIsPassedBackUnchanged() {
        ProxyForwarder.Result r = forwarder.forward("account", "GET", "/refuse", null, Map.of(), null);
        assertEquals(409, r.status());
    }

    @Test
    void aServiceThatIsNotRunningIs502ServiceUnreachable() {
        ProxyForwarder.Result r = forwarder.forward("ledger", "GET", "/health", null, Map.of(), null);
        assertEquals(502, r.status());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("\"code\":\"SERVICE_UNREACHABLE\""));
    }

    @Test
    void aServiceThatAnswersTooSlowlyIs504() {
        ProxyForwarder.Result r = forwarder.forward("account", "GET", "/slow", null, Map.of(), null);
        assertEquals(504, r.status());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("SERVICE_TIMEOUT"));
    }

    @Test
    void onlyConfiguredServicesAndPlainPathsAreReachable() {
        assertThrows(ProxyForwarder.UnknownServiceException.class,
                () -> forwarder.forward("evil", "GET", "/", null, Map.of(), null));
        assertEquals(400, forwarder.forward("account", "GET", "/api/../../etc", null, Map.of(), null).status());
        assertEquals(400, forwarder.forward("account", "GET", "//other-host/x", null, Map.of(), null).status());
        assertEquals(405, forwarder.forward("account", "TRACE", "/x", null, Map.of(), null).status());
    }

    @Test
    void aBurstReleasesAllCopiesTogether_andKeepsTheirOrder() throws IOException {
        HttpServer parallel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        parallel.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Set<String> requestIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        parallel.createContext("/", exchange -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            requestIds.add(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.contains("\"n\":3") ? 409 : 201, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        parallel.start();
        try {
            ProxyForwarder f = new ProxyForwarder(Map.of("account", URI.create("http://127.0.0.1:" + parallel.getAddress().getPort())),
                    Duration.ofSeconds(1), Duration.ofSeconds(5));
            java.util.List<byte[]> bodies = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                bodies.add(("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            }

            java.util.List<ProxyForwarder.Result> results = f.burst("account", "POST", "/x", null,
                    Map.of("X-Request-ID", "demo"), bodies);

            assertEquals(10, results.size());
            assertEquals("{\"n\":1}", new String(results.get(0).body(), StandardCharsets.UTF_8));
            assertEquals("{\"n\":10}", new String(results.get(9).body(), StandardCharsets.UTF_8));
            assertEquals(409, results.get(2).status());
            assertTrue(maxInFlight.get() >= 5, "copies ran at the same time, max in flight " + maxInFlight.get());
            assertEquals(10, requestIds.size());
            assertTrue(requestIds.contains("demo-1") && requestIds.contains("demo-10"));
        } finally {
            parallel.stop(0);
        }
    }

    @Test
    void aBurstNeedsBetweenOneAndAHundredBodies() {
        assertEquals(400, forwarder.burst("account", "POST", "/x", null, Map.of(), java.util.List.of()).get(0).status());
    }

    @Test
    void anEmptyPathGoesToTheServiceRoot() {
        assertEquals(URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/"),
                forwarder.target("account", "", null).orElseThrow());
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
