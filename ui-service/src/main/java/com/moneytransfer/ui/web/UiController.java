package com.moneytransfer.ui.web;

import com.moneytransfer.ui.config.UiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class UiController {

    private final UiProperties properties;

    public UiController(UiProperties properties) {
        this.properties = properties;
    }

    /** The services the page can reach and where they are, shown on the Overview tab. */
    @GetMapping("/api/ui/services")
    public List<Map<String, String>> services() {
        return properties.services().entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "url", e.getValue().toString()))
                .toList();
    }

    /** Same shape as the other services' /health. No database here, so it is UP whenever it runs. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "ui-service");
    }
}
