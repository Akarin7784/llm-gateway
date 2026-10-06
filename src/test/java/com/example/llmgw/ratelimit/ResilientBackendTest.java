package com.example.llmgw.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientBackendTest {

    private static final BucketConfig CONFIG = BucketConfig.of(10, 10);

    private static final class ThrowingBucket implements TokenBucket {
        @Override
        public Outcome tryTake(String key, long permits, BucketConfig config) {
            throw new IllegalStateException("redis is down");
        }

        @Override
        public void refund(String key, long permits, BucketConfig config) {
            throw new IllegalStateException("redis is down");
        }
    }

    private static final class ThrowingGate implements ConcurrencyGate {
        @Override
        public boolean tryAcquire(String key, String leaseId, int limit, Duration leaseTtl) {
            throw new IllegalStateException("redis is down");
        }

        @Override
        public void release(String key, String leaseId) {
            throw new IllegalStateException("redis is down");
        }
    }

    @Test
    void fallsBackToLocalBucketAndCountsTheDegradation() {
        AtomicInteger degraded = new AtomicInteger();
        TokenBucket resilient = new ResilientTokenBucket(new ThrowingBucket(),
                new InMemoryTokenBucket(), () -> degraded.incrementAndGet());

        assertThat(resilient.tryTake("k", 1, CONFIG).allowed()).isTrue();
        assertThat(degraded.get()).isEqualTo(1);
    }

    @Test
    void doesNotFallBackWhenPrimaryAnswers() {
        AtomicInteger degraded = new AtomicInteger();
        InMemoryTokenBucket local = new InMemoryTokenBucket();
        TokenBucket resilient = new ResilientTokenBucket(local, new InMemoryTokenBucket(),
                () -> degraded.incrementAndGet());

        assertThat(resilient.tryTake("k", 1, CONFIG).allowed()).isTrue();
        assertThat(degraded.get()).isZero();
    }

    @Test
    void concurrencyGateFallsBackToLocalLimit() {
        AtomicInteger degraded = new AtomicInteger();
        InMemoryConcurrencyGate local = new InMemoryConcurrencyGate();
        ConcurrencyGate resilient = new ResilientConcurrencyGate(new ThrowingGate(), local,
                () -> degraded.incrementAndGet());

        assertThat(resilient.tryAcquire("k", "a", 1, Duration.ofMinutes(5))).isTrue();
        assertThat(resilient.tryAcquire("k", "b", 1, Duration.ofMinutes(5))).isFalse();
        assertThat(degraded.get()).isEqualTo(2);

        resilient.release("k", "a");
        assertThat(resilient.tryAcquire("k", "c", 1, Duration.ofMinutes(5))).isTrue();
    }
}
