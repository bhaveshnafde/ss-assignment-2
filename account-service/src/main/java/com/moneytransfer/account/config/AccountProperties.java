package com.moneytransfer.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdStrategy atomic (safe, the default) or naive (deliberately broken, Challenge 1 demo only)
 * @param naiveDelayMs pause between read and write in naive mode, to make the race easy to show
 */
@ConfigurationProperties(prefix = "account")
public record AccountProperties(HoldStrategy holdStrategy, long naiveDelayMs) {

    public enum HoldStrategy { ATOMIC, NAIVE }

    public AccountProperties {
        if (holdStrategy == null) {
            holdStrategy = HoldStrategy.ATOMIC;
        }
    }
}
