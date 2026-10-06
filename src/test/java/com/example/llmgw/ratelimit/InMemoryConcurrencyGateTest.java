package com.example.llmgw.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryConcurrencyGateTest {

    private final InMemoryTokenBucketTest.AtomicClock clock = new InMemoryTokenBucketTest.AtomicClock(0);
    private final InMemoryConcurrencyGate gate = new InMemoryConcurrencyGate(clock::asLong);

    @Test
    void capsInFlightAndReleasesOnDone() {
        assertThat(gate.tryAcquire("t", "a", 2, Duration.ofMinutes(5))).isTrue();
        assertThat(gate.tryAcquire("t", "b", 2, Duration.ofMinutes(5))).isTrue();
        assertThat(gate.tryAcquire("t", "c", 2, Duration.ofMinutes(5))).isFalse();

        gate.release("t", "a");
        assertThat(gate.tryAcquire("t", "d", 2, Duration.ofMinutes(5))).isTrue();
    }

    /**
     * The scenario a plain counter cannot survive: a replica dies holding a slot. Without expiry the
     * tenant would be throttled down forever by a process that no longer exists.
     */
    @Test
    void expiredLeasesAreReclaimed() {
        Duration ttl = Duration.ofMillis(1_000);
        assertThat(gate.tryAcquire("t", "leaked", 1, ttl)).isTrue();
        assertThat(gate.tryAcquire("t", "waiting", 1, ttl)).isFalse();

        clock.addMillis(1_500);
        assertThat(gate.tryAcquire("t", "waiting", 1, ttl)).isTrue();
    }

    @Test
    void releasingTwiceIsHarmless() {
        gate.tryAcquire("t", "a", 1, Duration.ofMinutes(5));
        gate.release("t", "a");
        gate.release("t", "a");
        assertThat(gate.tryAcquire("t", "b", 1, Duration.ofMinutes(5))).isTrue();
    }
}
