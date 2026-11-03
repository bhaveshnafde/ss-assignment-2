package com.moneytransfer.limits.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param dailyLimit           DAILY_LIMIT: default BR3 limit per account per UTC day
 * @param perTransferLimit     PER_TRANSFER_LIMIT: default BR1 cap
 * @param velocityMaxTransfers VELOCITY_MAX_TRANSFERS: F1 fraud rule; 0 switches it off
 * @param velocityWindow       VELOCITY_WINDOW_SECONDS: F1 window
 */
@Validated
@ConfigurationProperties(prefix = "limits")
public record LimitsProperties(
        @NotNull @Positive BigDecimal dailyLimit,
        @NotNull @Positive BigDecimal perTransferLimit,
        @Min(0) int velocityMaxTransfers,
        @NotNull Duration velocityWindow) { }
