package com.moneytransfer.notification.provider;

import com.moneytransfer.notification.domain.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The stand-in for a real SMS/email gateway (assignment condition e: a dependency that can be slow
 * or fail). Nothing leaves this machine: a "sent" message is written to the log.
 *
 * Three switches, changeable at runtime:
 * <ul>
 *   <li>delayMs: wait before answering (slow provider)</li>
 *   <li>failRate: share of sends that fail at random (unreliable provider)</li>
 *   <li>failNext: the next N sends fail, then sending works again (deterministic demo of retries)</li>
 * </ul>
 * Plain Java (no Spring), so it can be unit-tested with a fixed random generator and no real sleeping.
 */
public class SimulatedProvider {

    public record Settings(long delayMs, double failRate, int failNext) {
        public Settings {
            if (delayMs < 0 || delayMs > 60_000) {
                throw new IllegalArgumentException("delayMs must be between 0 and 60000");
            }
            if (failRate < 0.0 || failRate > 1.0) {
                throw new IllegalArgumentException("failRate must be between 0.0 and 1.0");
            }
            if (failNext < 0) {
                throw new IllegalArgumentException("failNext must not be negative");
            }
        }
    }

    /** What the provider answered. A failure is a normal result, not an exception. */
    public record Result(boolean delivered, String providerMessageId, String error) {
        static Result delivered(String id) { return new Result(true, id, null); }
        static Result failed(String error) { return new Result(false, null, error); }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(SimulatedProvider.class);

    private final AtomicReference<Settings> settings;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    public SimulatedProvider(Settings initial, RandomGenerator random, Sleeper sleeper) {
        this.settings = new AtomicReference<>(Objects.requireNonNull(initial));
        this.random = Objects.requireNonNull(random);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public Settings settings() {
        return settings.get();
    }

    public Settings update(Settings next) {
        settings.set(Objects.requireNonNull(next));
        log.warn("Simulated provider settings changed: {}", next);
        return next;
    }

    public Result send(Channel channel, String accountId, String message) {
        Settings s = settings.get();
        if (s.delayMs() > 0) {
            try {
                sleeper.sleep(s.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failed("interrupted while waiting for the provider");
            }
        }
        if (consumeForcedFailure()) {
            log.warn("Simulated provider: forced failure for {} to {}", channel, accountId);
            return Result.failed("simulated provider outage (failNext)");
        }
        if (s.failRate() > 0 && random.nextDouble() < s.failRate()) {
            log.warn("Simulated provider: random failure for {} to {} (failRate {})", channel, accountId, s.failRate());
            return Result.failed("simulated provider error (failRate " + s.failRate() + ")");
        }
        String id = channel + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Simulated provider: {} {} to {}: \"{}\"", id, channel, accountId, message);
        return Result.delivered(id);
    }

    /** Takes one forced failure off the counter, if any are left. Safe when called from many threads. */
    private boolean consumeForcedFailure() {
        while (true) {
            Settings current = settings.get();
            if (current.failNext() <= 0) {
                return false;
            }
            Settings next = new Settings(current.delayMs(), current.failRate(), current.failNext() - 1);
            if (settings.compareAndSet(current, next)) {
                return true;
            }
        }
    }
}
