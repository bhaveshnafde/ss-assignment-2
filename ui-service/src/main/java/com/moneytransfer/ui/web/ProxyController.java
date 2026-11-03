package com.moneytransfer.ui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.ui.proxy.ProxyForwarder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page calls {@code /proxy/<service>/<path>}, for example
 * {@code GET /proxy/account/api/v1/accounts} goes to {@code GET http://localhost:8081/api/v1/accounts}.
 * The service's answer (status, body, Idempotent-Replayed, X-Request-ID) comes back unchanged.
 */
@RestController
public class ProxyController {

    /**
     * One call to fire N times at once, e.g. 25 holds against one account (Challenge 1).
     * {@code bodies} holds one JSON body per copy; use the same body N times for identical requests.
     */
    public record BurstRequest(String service, String method, String path, String query,
                               Map<String, String> headers, List<JsonNode> bodies) { }

    private final ProxyForwarder forwarder;
    private final ObjectMapper json;

    public ProxyController(ProxyForwarder forwarder, ObjectMapper json) {
        this.forwarder = forwarder;
        this.json = json;
    }

    @RequestMapping("/proxy/{service}/**")
    public ResponseEntity<byte[]> forward(@PathVariable String service, HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body) {
        String prefix = request.getContextPath() + "/proxy/" + service;
        String path = request.getRequestURI().substring(prefix.length());

        Map<String, String> headers = new HashMap<>();
        for (String name : new String[] {"Content-Type", "Accept", "Idempotency-Key", "X-Request-ID"}) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }

        ProxyForwarder.Result result = forwarder.forward(service, request.getMethod(), path,
                request.getQueryString(), headers, body);

        HttpHeaders out = new HttpHeaders();
        result.headers().forEach(out::set);
        return ResponseEntity.status(result.status()).headers(out).body(result.body());
    }

    /**
     * Fires the same call once per body, all released at the same instant by the server, and returns
     * every answer in order: [{status, headers, body}, ...].
     */
    @PostMapping("/api/ui/burst")
    public List<Map<String, Object>> burst(@RequestBody BurstRequest request,
                                           @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        Map<String, String> headers = new HashMap<>(request.headers() == null ? Map.of() : request.headers());
        if (requestId != null) {
            headers.put("X-Request-ID", requestId);
        }
        List<byte[]> bodies = new ArrayList<>();
        if (request.bodies() != null) {
            for (JsonNode body : request.bodies()) {
                bodies.add(body == null || body.isNull() ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        List<Map<String, Object>> answers = new ArrayList<>();
        for (ProxyForwarder.Result r : forwarder.burst(request.service(), request.method(), request.path(),
                request.query(), headers, bodies)) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", r.status());
            answer.put("headers", r.headers());
            answer.put("body", parse(r.body()));
            answers.add(answer);
        }
        return answers;
    }

    private Object parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (java.io.IOException e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @ExceptionHandler(ProxyForwarder.UnknownServiceException.class)
    ResponseEntity<Map<String, Object>> unknownService(ProxyForwarder.UnknownServiceException e) {
        return ResponseEntity.status(404).body(Map.of("error", Map.of(
                "code", "UNKNOWN_SERVICE", "message", e.getMessage(), "details", java.util.List.of())));
    }
}
