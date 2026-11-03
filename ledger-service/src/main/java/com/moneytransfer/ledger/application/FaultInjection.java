package com.moneytransfer.ledger.application;

import com.moneytransfer.ledger.config.LedgerProperties;
import com.moneytransfer.ledger.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo switches for the Transfer saga (Challenge 3C) and retry behaviour. Only POSTING a journal
 * is affected; reversals always work, so the saga's undo can complete while posts are failing.
 *
 * Starts from LEDGER_FAIL / LEDGER_DELAY_MS and can be changed at runtime via /api/v1/admin/faults.
 */
@Component
public class FaultInjection {

    public record Settings(boolean failPosts, long postDelayMs) { }

    private static final Logger log = LoggerFactory.getLogger(FaultInjection.class);

    private final AtomicBoolean failPosts;
    private final AtomicLong postDelayMs;

    public FaultInjection(LedgerProperties properties) {
        this.failPosts = new AtomicBoolean(properties.failPosts());
        this.postDelayMs = new AtomicLong(Math.max(0, properties.postDelayMs()));
        if (properties.failPosts() || properties.postDelayMs() > 0) {
            log.warn("Ledger fault injection ON at startup: {}", current());
        }
    }

    public Settings current() {
        return new Settings(failPosts.get(), postDelayMs.get());
    }

    public Settings update(Settings settings) {
        failPosts.set(settings.failPosts());
        postDelayMs.set(Math.max(0, settings.postDelayMs()));
        log.warn("Ledger fault injection changed: {}", current());
        return current();
    }

    /** Called before posting: simulates the Ledger being down. */
    public void beforePost() {
        if (failPosts.get()) {
            log.warn("Refusing journal post: fault injection failPosts=true");
            throw new ApiException(503, "LEDGER_FORCED_FAILURE", "Ledger is switched to fail journal posts (demo)");
        }
    }

    /** Called after a NEW journal is saved: simulates a slow answer. The journal already exists. */
    public void afterNewPost() {
        long delay = postDelayMs.get();
        if (delay > 0) {
            log.warn("Delaying journal post answer by {} ms (fault injection)", delay);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
