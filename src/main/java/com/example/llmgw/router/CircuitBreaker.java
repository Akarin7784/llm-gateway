package com.example.llmgw.router;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Trips on a windowed error rate rather than on consecutive failures, because LLM traffic is bursty:
 * a 60% failure rate should not have to wait for a streak to be noticed.
 *
 * The volume floor is the part that matters most in practice: at 1 a.m. with two requests, one
 * timeout is a 50% error rate, and an unwary breaker would spend the whole night refusing the only
 * healthy upstream.
 *
 * Half-open probing is bounded by <em>attempts granted</em>, never by in-flight reservations: a
 * caller that dies between being granted and reporting would otherwise strand the breaker in
 * HALF_OPEN with no timer to get out.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final GatewayProperties.Routing config;
    private final UpstreamHealth health;
    private final GatewayMetrics metrics;
    private final LongSupplier clockMillis;
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;
    private long openedAtMillis;
    private int probeAttempts;
    private int probeSuccesses;
    private int consecutiveFailures;

    public CircuitBreaker(String name, GatewayProperties.Routing config, UpstreamHealth health,
                          GatewayMetrics metrics, LongSupplier clockMillis) {
        this.name = name;
        this.config = config;
        this.health = health;
        this.metrics = metrics;
        this.clockMillis = clockMillis;
    }

    /**
     * Whether the breaker is still in its cooldown. Read-only: eligibility screening must not consume
     * the half-open probe budget of upstreams a request never ends up attempting.
     */
    public boolean coolingDown() {
        lock.lock();
        try {
            return state == State.OPEN
                    && clockMillis.getAsLong() - openedAtMillis < config.getOpenDuration().toMillis();
        } finally {
            lock.unlock();
        }
    }

    public boolean allowRequest() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return true;
            }
            if (state == State.OPEN) {
                if (clockMillis.getAsLong() - openedAtMillis < config.getOpenDuration().toMillis()) {
                    return false;
                }
                transition(State.HALF_OPEN);
                probeAttempts = 0;
                probeSuccesses = 0;
            }
            if (probeAttempts >= config.getHalfOpenProbes()) {
                // The window passed without the probes being reported back at all: re-open rather than
                // sit in HALF_OPEN indefinitely.
                trip();
                return false;
            }
            // Counted at grant time, so a caller that never reports still consumes the budget.
            probeAttempts++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Records the outcome and re-evaluates the trip condition against the rolling window. */
    public void onResponse(boolean failed, long latencyMillis) {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                if (failed) {
                    health.recordFailure();
                    metrics.upstreamProbeResult(name, "failed");
                    trip();
                    return;
                }
                health.recordSuccess(latencyMillis);
                probeSuccesses++;
                metrics.upstreamProbeResult(name, "ok");
                if (probeSuccesses >= config.getHalfOpenProbes()) {
                    transition(State.CLOSED);
                    consecutiveFailures = 0;
                    health.reset();
                }
                return;
            }

            if (failed) {
                health.recordFailure();
                consecutiveFailures++;
                boolean rateTrip = health.volume() >= config.getMinVolume()
                        && health.errorRate() >= config.getErrorThreshold();
                // A streak guard alongside the rate guard: scoring diverts traffic away from a failing
                // vendor, so its window may never hold enough samples for a rate to be computed. Three
                // consecutive failures is a verdict a rate-only breaker would never reach at low traffic.
                boolean streakTrip = consecutiveFailures >= config.getConsecutiveFailureThreshold();
                if (state == State.CLOSED && (rateTrip || streakTrip)) {
                    trip();
                }
            } else {
                health.recordSuccess(latencyMillis);
                consecutiveFailures = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    private void trip() {
        transition(State.OPEN);
        openedAtMillis = clockMillis.getAsLong();
        probeAttempts = 0;
        probeSuccesses = 0;
        consecutiveFailures = 0;
    }

    private void transition(State next) {
        if (state != next) {
            metrics.circuitState(name, state.name(), next.name());
            state = next;
        }
    }

    public State state() {
        return state;
    }
}
