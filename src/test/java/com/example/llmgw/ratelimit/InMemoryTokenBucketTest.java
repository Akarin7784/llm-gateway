package com.example.llmgw.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketTest {

    private final AtomicClock clock = new AtomicClock(0);
    private final InMemoryTokenBucket bucket = new InMemoryTokenBucket(clock::asLong);

    @Test
    void drainsThenRejects() {
        BucketConfig config = BucketConfig.of(3, 3);

        assertThat(bucket.tryTake("k", 1, config).allowed()).isTrue();
        assertThat(bucket.tryTake("k", 1, config).allowed()).isTrue();
        assertThat(bucket.tryTake("k", 1, config).allowed()).isTrue();

        TokenBucket.Outcome denied = bucket.tryTake("k", 1, config);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isPositive();
    }

    @Test
    void becomesAvailableAgainAfterRefill() {
        BucketConfig config = BucketConfig.of(1, 60);
        assertThat(bucket.tryTake("k", 1, config).allowed()).isTrue();
        assertThat(bucket.tryTake("k", 1, config).allowed()).isFalse();

        clock.addMillis(1_000);
        assertThat(bucket.tryTake("k", 1, config).allowed()).isTrue();
    }

    @Test
    void refundGivesBackButNeverAboveCapacity() {
        BucketConfig config = BucketConfig.of(10, 10);
        bucket.tryTake("k", 10, config);
        assertThat(bucket.tryTake("k", 1, config).allowed()).isFalse();

        bucket.refund("k", 4, config);
        assertThat(bucket.tryTake("k", 4, config).allowed()).isTrue();

        bucket.refund("k", 100, config);
        assertThat(bucket.tryTake("k", 11, config).allowed()).isFalse();
        assertThat(bucket.tryTake("k", 10, config).allowed()).isTrue();
    }

    @Test
    void separateKeysDoNotShareState() {
        BucketConfig config = BucketConfig.of(1, 1);
        assertThat(bucket.tryTake("a", 1, config).allowed()).isTrue();
        assertThat(bucket.tryTake("b", 1, config).allowed()).isTrue();
    }

    /** The point of the atomic take: without it a burst would all read the same full bucket. */
    @Test
    void concurrentTakersNeverExceedCapacity() throws Exception {
        BucketConfig config = BucketConfig.of(50, 50);
        int callers = 400;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    if (bucket.tryTake("burst", 1, config).allowed()) {
                        allowed.incrementAndGet();
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
        }

        assertThat(allowed.get()).isEqualTo(50);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class AtomicClock {
        private long millis;

        AtomicClock(long start) {
            this.millis = start;
        }

        long asLong() {
            return millis;
        }

        void addMillis(long delta) {
            millis += delta;
        }
    }
}
