package com.moneytransfer.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider     starting settings of the simulated provider (NOTIFY_DELAY_MS, NOTIFY_FAIL_RATE, NOTIFY_FAIL_NEXT)
 * @param adminEnabled ADMIN_ENDPOINTS_ENABLED: allow changing them at runtime
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Provider provider, boolean adminEnabled) {

    public record Provider(long delayMs, double failRate, int failNext) { }

    public NotificationProperties {
        if (provider == null) {
            provider = new Provider(0, 0.0, 0);
        }
    }
}
