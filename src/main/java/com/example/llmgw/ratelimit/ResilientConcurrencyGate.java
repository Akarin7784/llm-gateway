package com.example.llmgw.ratelimit;

import java.time.Duration;

/** Same fallback policy as {@link ResilientTokenBucket}, applied to the in-flight ceiling. */
public class ResilientConcurrencyGate implements ConcurrencyGate {

    private final ConcurrencyGate primary;
    private final ConcurrencyGate fallback;
    private final Runnable fallbackListener;

    public ResilientConcurrencyGate(ConcurrencyGate primary, ConcurrencyGate fallback, Runnable fallbackListener) {
        this.primary = primary;
        this.fallback = fallback;
        this.fallbackListener = fallbackListener;
    }

    @Override
    public boolean tryAcquire(String key, String leaseId, int limit, Duration leaseTtl) {
        try {
            return primary.tryAcquire(key, leaseId, limit, leaseTtl);
        } catch (RuntimeException e) {
            fallbackListener.run();
            return fallback.tryAcquire(key, leaseId, limit, leaseTtl);
        }
    }

    @Override
    public void release(String key, String leaseId) {
        try {
            primary.release(key, leaseId);
        } catch (RuntimeException e) {
            fallbackListener.run();
            fallback.release(key, leaseId);
        }
    }
}
