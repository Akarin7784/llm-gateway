package com.example.llmgw.ratelimit;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Single-instance bucket. Correct within one process, and permissive across several gateway
 * replicas, which is why it is layered under the Redis bucket rather than replaced by it: an
 * instance-local ceiling still bounds memory and upstream connections if Redis is unavailable.
 *
 * Locks are explicit because a blocking {@code synchronized} block pins the carrier thread of a
 * virtual thread on JDK 21.
 */
@Component
public class InMemoryTokenBucket implements TokenBucket {

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemoryTokenBucket() {
        this(System::currentTimeMillis);
    }

    InMemoryTokenBucket(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public Outcome tryTake(String key, long permits, BucketConfig config) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
        slot.lock.lock();
        try {
            long now = clock.getAsLong();
            if (!slot.initialised) {
                // A fresh bucket starts full, matching the Lua script; starting empty would reject
                // every first request in a window and silently disagree with the distributed path.
                slot.tokens = config.capacity();
                slot.lastRefillMillis = now;
                slot.initialised = true;
            }
            double tokens = TokenBucketMath.refill(slot.tokens, slot.lastRefillMillis, now, config);
            if (tokens >= permits) {
                tokens -= permits;
                slot.tokens = tokens;
                slot.lastRefillMillis = now;
                return Outcome.allow(tokens);
            }
            slot.tokens = tokens;
            slot.lastRefillMillis = now;
            return Outcome.deny(tokens, TokenBucketMath.millisUntilAvailable(tokens, permits, config));
        } finally {
            slot.lock.unlock();
        }
    }

    @Override
    public void refund(String key, long permits, BucketConfig config) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return;
        }
        slot.lock.lock();
        try {
            long now = clock.getAsLong();
            if (!slot.initialised) {
                slot.tokens = config.capacity();
                slot.lastRefillMillis = now;
                slot.initialised = true;
            }
            double tokens = TokenBucketMath.refill(slot.tokens, slot.lastRefillMillis, now, config);
            slot.tokens = Math.min(config.capacity(), tokens + permits);
            slot.lastRefillMillis = now;
        } finally {
            slot.lock.unlock();
        }
    }

    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private double tokens;
        private long lastRefillMillis;
        private boolean initialised;
    }
}
