package com.moneytransfer.notification.provider;

import com.moneytransfer.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The simulated provider behaves exactly as its three switches say (no real sleeping). */
class SimulatedProviderTest {

    private final List<Long> sleeps = new ArrayList<>();

    private SimulatedProvider provider(long delayMs, double failRate, int failNext) {
        return new SimulatedProvider(new SimulatedProvider.Settings(delayMs, failRate, failNext), new Random(7), sleeps::add);
    }

    private static SimulatedProvider.Result send(SimulatedProvider p) {
        return p.send(Channel.SMS, "ACC-1", "hello");
    }

    @Test
    void normalSettingsDeliverWithAProviderId() {
        SimulatedProvider.Result r = send(provider(0, 0.0, 0));
        assertTrue(r.delivered());
        assertTrue(r.providerMessageId().startsWith("SMS-"));
    }

    @Test
    void failNextFailsExactlyThatManyThenRecovers() {
        SimulatedProvider p = provider(0, 0.0, 2);
        assertFalse(send(p).delivered());
        assertFalse(send(p).delivered());
        assertTrue(send(p).delivered());
        assertEquals(0, p.settings().failNext());
    }

    @Test
    void failRateOneAlwaysFailsAndZeroNeverFails() {
        SimulatedProvider always = provider(0, 1.0, 0);
        SimulatedProvider never = provider(0, 0.0, 0);
        for (int i = 0; i < 50; i++) {
            assertFalse(send(always).delivered());
            assertTrue(send(never).delivered());
        }
    }

    @Test
    void failRateHalfFailsRoughlyHalf() {
        SimulatedProvider p = provider(0, 0.5, 0);
        int failed = 0;
        for (int i = 0; i < 1000; i++) {
            if (!send(p).delivered()) failed++;
        }
        assertTrue(failed > 400 && failed < 600, "failed " + failed + " of 1000");
    }

    @Test
    void delayIsAppliedOnEverySend() {
        SimulatedProvider p = provider(5000, 0.0, 0);
        send(p);
        send(p);
        assertEquals(List.of(5000L, 5000L), sleeps);
    }

    @Test
    void invalidSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedProvider.Settings(0, 1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedProvider.Settings(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedProvider.Settings(0, 0, -1));
    }
}
