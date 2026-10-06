package com.example.llmgw.ratelimit;

/**
 * Prefers the shared bucket and falls back to the instance-local one when Redis cannot answer.
 *
 * Fail-open would let a Redis outage turn into an unbounded spend, and fail-closed would turn it
 * into a total outage; the middle path is to keep enforcing the per-instance ceiling, which costs
 * precision across replicas but never zero. The fallback counter is what makes that trade visible
 * in the dashboards rather than silent.
 */
public class ResilientTokenBucket implements TokenBucket {

    private final TokenBucket primary;
    private final TokenBucket fallback;
    private final Runnable fallbackListener;

    public ResilientTokenBucket(TokenBucket primary, TokenBucket fallback, Runnable fallbackListener) {
        this.primary = primary;
        this.fallback = fallback;
        this.fallbackListener = fallbackListener;
    }

    @Override
    public TokenBucket.Outcome tryTake(String key, long permits, BucketConfig config) {
        try {
            return primary.tryTake(key, permits, config);
        } catch (RuntimeException e) {
            fallbackListener.run();
            return fallback.tryTake(key, permits, config);
        }
    }

    @Override
    public void refund(String key, long permits, BucketConfig config) {
        try {
            primary.refund(key, permits, config);
        } catch (RuntimeException e) {
            fallbackListener.run();
            fallback.refund(key, permits, config);
        }
    }
}
