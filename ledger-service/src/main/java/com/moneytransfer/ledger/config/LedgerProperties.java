package com.moneytransfer.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param failPosts    LEDGER_FAIL: POST /journals answers 503 (Challenge 3C demo)
 * @param postDelayMs  LEDGER_DELAY_MS: wait after saving a new journal before answering (retry demo)
 * @param adminEnabled ADMIN_ENDPOINTS_ENABLED: allow changing the two above at runtime
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(boolean failPosts, long postDelayMs, boolean adminEnabled) { }
