package com.example.llmgw.router;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final GatewayProperties.Routing config = new GatewayProperties.Routing();
    private final UpstreamHealth health = new UpstreamHealth(10, now::get);
    private final CircuitBreaker breaker = new CircuitBreaker("mock-upstream", config, health,
            new GatewayMetrics(new SimpleMeterRegistry()), now::get);

    @BeforeEach
    void configure() {
        config.setMinVolume(4);
        config.setErrorThreshold(0.5);
        config.setOpenDuration(Duration.ofSeconds(10));
        config.setHalfOpenProbes(2);
    }

    /** Low traffic must not be read as ill health: one timeout at 1 a.m. is a 100% error rate. */
    @Test
    void staysClosedBelowTheVolumeFloor() {
        breaker.onResponse(true, 0);
        breaker.onResponse(true, 0);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    /**
     * The rate guard alone would never fire here: scoring sends a failing vendor so little traffic
     * that its window cannot fill. The streak guard is what still gets a verdict.
     */
    @Test
    void tripsOnAStreakEvenWhenVolumeIsUnreachable() {
        config.setMinVolume(1000);
        breaker.onResponse(true, 0);
        breaker.onResponse(true, 0);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        breaker.onResponse(true, 0);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aSuccessBreaksTheStreak() {
        config.setMinVolume(1000);
        breaker.onResponse(true, 0);
        breaker.onResponse(true, 0);
        breaker.onResponse(false, 30);
        breaker.onResponse(true, 0);
        breaker.onResponse(true, 0);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensOnceTheWindowIsBadEnough() {
        for (int i = 0; i < 4; i++) {
            breaker.onResponse(true, 0);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void staysClosedWhenErrorsAreUnderTheThreshold() {
        for (int i = 0; i < 8; i++) {
            breaker.onResponse(i < 2, 50);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensForTheWholeCooldownThenProbes() {
        trip();
        now.set(9_000);
        assertThat(breaker.allowRequest()).isFalse();

        now.set(11_000);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void closesAfterEnoughSuccessfulProbesAndClearsTheOutageSamples() {
        trip();
        now.set(11_000);
        breaker.allowRequest();
        breaker.onResponse(false, 40);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        breaker.allowRequest();
        breaker.onResponse(false, 40);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(health.volume()).as("stale failures would reopen it on the next error").isZero();
    }

    @Test
    void aFailedProbeSendsItStraightBackToOpen() {
        trip();
        now.set(11_000);
        breaker.allowRequest();
        breaker.onResponse(true, 0);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        now.set(15_000);
        assertThat(breaker.allowRequest()).isFalse();
    }

    /**
     * The case a half-open counter has to survive: probes are granted but never reported back, so
     * nothing would ever move the breaker out of HALF_OPEN.
     */
    @Test
    void unreportedProbesDoNotStrandTheBreaker() {
        trip();
        now.set(11_000);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isTrue();

        now.set(12_000);
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void trip() {
        for (int i = 0; i < 6; i++) {
            breaker.onResponse(true, 0);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
