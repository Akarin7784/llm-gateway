package com.example.llmgw.router;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamHealthTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;
    private final UpstreamHealth health = new UpstreamHealth(10, clock);

    @Test
    void countsSuccessesAndFailuresWithinTheWindow() {
        health.recordSuccess(100);
        health.recordSuccess(200);
        health.recordFailure();

        assertThat(health.volume()).isEqualTo(3);
        assertThat(health.errorRate()).isCloseTo(1 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(health.meanLatencyMillis()).isEqualTo(150);
    }

    /** The reason a window is used instead of a lifetime counter. */
    @Test
    void samplesFallOutOfTheWindow() {
        for (int i = 0; i < 20; i++) {
            health.recordFailure();
        }
        assertThat(health.volume()).isEqualTo(20);

        now.set(11_000);
        assertThat(health.volume()).isZero();
        assertThat(health.errorRate()).isZero();
    }

    @Test
    void eachSecondIsItsOwnBucket() {
        health.recordFailure();
        now.set(1_000);
        health.recordSuccess(50);
        now.set(2_000);
        health.recordSuccess(50);

        assertThat(health.volume()).isEqualTo(3);
        assertThat(health.meanLatencyMillis()).isEqualTo(50);
    }

    @Test
    void resetClearsEverything() {
        health.recordFailure();
        health.recordFailure();
        health.reset();
        assertThat(health.volume()).isZero();
    }

    /**
     * Slots are addressed as second mod bucketCount, so a slot can also carry a sample from exactly
     * one full rotation earlier. Without the staleness check that ghost sample would land inside the
     * current window.
     */
    @Test
    void staleRotationDoesNotLeakIntoTheWindow() {
        health.recordFailure();
        now.set(12_000);
        assertThat(health.volume()).as("12s later the sample is outside a 10s window").isZero();
    }
}
