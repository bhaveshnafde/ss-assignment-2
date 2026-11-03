package com.moneytransfer.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Bound from the {@code ui:} block in application.yml (every value can be overridden by an env var).
 *
 * @param services       name to base URL, e.g. account = http://localhost:8081. The page can only reach these.
 * @param connectTimeout wait this long for a connection before reporting the service as not running
 * @param requestTimeout wait this long for an answer. Long, because a transfer may retry and compensate.
 */
@ConfigurationProperties(prefix = "ui")
public record UiProperties(Map<String, URI> services, Duration connectTimeout, Duration requestTimeout) {
}
