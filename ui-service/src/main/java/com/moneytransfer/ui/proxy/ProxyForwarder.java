package com.moneytransfer.ui.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Forwards one call from the dashboard page to one of the five services and hands back exactly what
 * that service answered (status, body and the headers the page shows).
 *
 * Why a forwarder at all: the page is served from this service (port 8080), and a browser will not let
 * a page call other ports without CORS headers, which the five services don't send. Going through here
 * also means the page only needs one address.
 *
 * Plain Java (no Spring), so it can be unit-tested with a tiny in-process HTTP server.
 */
public final class ProxyForwarder {

    /** What the service answered, or a 502/504 in the team error format if it gave no answer. */
    public record Result(int status, Map<String, String> headers, byte[] body) { }

    /** Thrown for a service name that is not configured: the forwarder only talks to the five known services. */
    public static final class UnknownServiceException extends RuntimeException {
        public UnknownServiceException(String service) {
            super("unknown service: " + service);
        }
    }

    /** Request headers passed on to the service; everything else (cookies, browser headers) is dropped. */
    static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Idempotency-Key", "X-Request-ID");

    /** Response headers passed back to the page; the demo shows Idempotent-Replayed and X-Request-ID. */
    static final List<String> RETURNED_RESPONSE_HEADERS = List.of("Content-Type", "Idempotent-Replayed", "X-Request-ID", "Location");

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Map<String, URI> services;
    private final HttpClient client;
    private final Duration requestTimeout;

    /**
     * @param services       service name (e.g. "account") to base URL (e.g. http://localhost:8081)
     * @param connectTimeout how long to wait for a connection before answering 502
     * @param requestTimeout how long to wait for an answer before answering 504
     */
    public ProxyForwarder(Map<String, URI> services, Duration connectTimeout, Duration requestTimeout) {
        this.services = Map.copyOf(services);
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Set<String> serviceNames() {
        return services.keySet();
    }

    /**
     * The full URL a call goes to, or empty if the path is not allowed.
     * Only absolute paths without ".." are accepted, so a call can never leave the service's own API.
     */
    Optional<URI> target(String service, String path, String query) {
        URI base = services.get(service);
        if (base == null) {
            throw new UnknownServiceException(service);
        }
        String p = (path == null || path.isEmpty()) ? "/" : path;
        if (!p.startsWith("/") || p.contains("..") || p.contains("\\") || p.contains("//")) {
            return Optional.empty();
        }
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        String url = root + p + (query == null || query.isEmpty() ? "" : "?" + query);
        try {
            return Optional.of(URI.create(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Sends the call and returns the service's answer as it is, including 4xx and 5xx.
     * Only a missing answer is turned into an error: 502 SERVICE_UNREACHABLE (not running or refused)
     * or 504 SERVICE_TIMEOUT (no answer in time).
     */
    public Result forward(String service, String method, String path, String query,
                          Map<String, String> requestHeaders, byte[] body) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) {
            return error(405, "METHOD_NOT_ALLOWED", "method " + method + " is not forwarded");
        }
        Optional<URI> uri = target(service, path, query);
        if (uri.isEmpty()) {
            return error(400, "INVALID_PATH", "path not allowed: " + path);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri.get())
                .timeout(requestTimeout)
                .method(m, body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = header(requestHeaders, name);
            if (value != null && !value.isBlank()) {
                request.header(name, value);
            }
        }

        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : RETURNED_RESPONSE_HEADERS) {
                response.headers().firstValue(name).ifPresent(v -> headers.put(name, v));
            }
            return new Result(response.statusCode(), headers, response.body());
        } catch (HttpConnectTimeoutException e) {
            return error(502, "SERVICE_UNREACHABLE", service + " service did not accept a connection at " + uri.get().getAuthority());
        } catch (HttpTimeoutException e) {
            return error(504, "SERVICE_TIMEOUT", service + " service gave no answer within " + requestTimeout.toSeconds() + " s");
        } catch (ConnectException e) {
            return error(502, "SERVICE_UNREACHABLE", service + " service is not running at " + uri.get().getAuthority());
        } catch (IOException e) {
            return error(502, "SERVICE_UNREACHABLE", service + " service: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, "INTERRUPTED", "the call was interrupted");
        }
    }

    /** Most copies one burst may fire. */
    public static final int MAX_BURST = 100;

    /**
     * Sends one call N times (one body per copy) and releases all copies at the same instant, the way
     * the race scripts do with {@code xargs -P}. A browser would only send about 6 at a time to one
     * server, which hides the race the demo is about.
     *
     * Each copy gets its own X-Request-ID ({@code <given id>-1}, {@code -2}, ...) so it can be found in
     * the service's logs. Results come back in the same order as the bodies.
     */
    public List<Result> burst(String service, String method, String path, String query,
                              Map<String, String> requestHeaders, List<byte[]> bodies) {
        if (bodies == null || bodies.isEmpty() || bodies.size() > MAX_BURST) {
            return List.of(error(400, "INVALID_BURST", "a burst needs 1 to " + MAX_BURST + " bodies"));
        }
        target(service, path, query);   // unknown service: fail before starting any thread
        String baseId = header(requestHeaders, "X-Request-ID");
        java.util.concurrent.CountDownLatch startTogether = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Result>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                byte[] body = bodies.get(i);
                Map<String, String> headers = new LinkedHashMap<>(requestHeaders == null ? Map.of() : requestHeaders);
                headers.keySet().removeIf(k -> k.equalsIgnoreCase("X-Request-ID"));
                headers.put("X-Request-ID", (baseId == null ? "ui-burst" : baseId) + "-" + (i + 1));
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return forward(service, method, path, query, headers, body);
                }));
            }
            startTogether.countDown();
            List<Result> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Result> f : futures) {
                try {
                    results.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    results.add(error(502, "SERVICE_UNREACHABLE", String.valueOf(e.getCause())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(error(503, "INTERRUPTED", "the burst was interrupted"));
                }
            }
            return results;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** An error in the team's shared format, so the page reads every error the same way. */
    static Result error(int status, String code, String message) {
        String json = "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"requestId\":null,\"details\":[]}}";
        return new Result(status, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
