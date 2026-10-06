package com.example.llmgw.router;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Rolling one-second buckets over a fixed coverage window: enough history for an error rate to mean
 * something, short enough that a recovery shows up in seconds rather than minutes.
 *
 * A cumulative counter would be the wrong instrument -- after an hour of healthy traffic, an outage
 * would be 0.1% of lifetime requests and would never trip anything.
 */
public class UpstreamHealth {

    private static final int BUCKETS = 12;

    private final Bucket[] buckets = new Bucket[BUCKETS];
    private final LongSupplier clockMillis;
    private final int windowSeconds;

    public UpstreamHealth(int windowSeconds, LongSupplier clockMillis) {
        this.windowSeconds = Math.min(windowSeconds, BUCKETS);
        this.clockMillis = clockMillis;
        for (int i = 0; i < BUCKETS; i++) {
            buckets[i] = new Bucket();
        }
    }

    public void recordSuccess(long latencyMillis) {
        Bucket bucket = current();
        bucket.successes.incrementAndGet();
        bucket.latencySum.addAndGet(latencyMillis);
        bucket.latencyCount.incrementAndGet();
    }

    public void recordFailure() {
        current().failures.incrementAndGet();
    }

    /**
     * Called when a breaker closes after successful probes: the outage samples that opened it must not
     * still be sitting in the window, or the first error afterwards would reopen it immediately.
     */
    public void reset() {
        for (Bucket bucket : buckets) {
            bucket.second.set(Long.MIN_VALUE);
            bucket.successes.set(0);
            bucket.failures.set(0);
            bucket.latencySum.set(0);
            bucket.latencyCount.set(0);
        }
    }

    private Bucket current() {
        long nowSecond = clockMillis.getAsLong() / 1000;
        Bucket bucket = buckets[Math.floorMod(nowSecond, BUCKETS)];
        if (bucket.second.get() != nowSecond && resetStale(bucket, nowSecond)) {
            bucket.successes.set(0);
            bucket.failures.set(0);
            bucket.latencySum.set(0);
            bucket.latencyCount.set(0);
        }
        return bucket;
    }

    /**
     * A CAS that fails just means another thread rolled this bucket already; either way the slot now
     * belongs to {@code nowSecond}.
     */
    private boolean resetStale(Bucket bucket, long nowSecond) {
        long observed = bucket.second.get();
        return bucket.second.compareAndSet(observed, nowSecond);
    }

    public long volume() {
        return successes() + failures();
    }

    public long successes() {
        long total = 0;
        for (Bucket bucket : inWindow()) {
            total += bucket.successes.get();
        }
        return total;
    }

    public long failures() {
        long total = 0;
        for (Bucket bucket : inWindow()) {
            total += bucket.failures.get();
        }
        return total;
    }

    public double errorRate() {
        long volume = volume();
        return volume == 0 ? 0 : (double) failures() / volume;
    }

    public double meanLatencyMillis() {
        long count = 0;
        long sum = 0;
        for (Bucket bucket : inWindow()) {
            count += bucket.latencyCount.get();
            sum += bucket.latencySum.get();
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    /**
     * Slots are addressed by second modulo bucket count, so a slot can also hold a value from a full
     * rotation earlier. The {@code second} tag is what distinguishes the two; reading a slot without
     * checking it would silently include an 11-second-old sample in a 10-second window.
     */
    private Bucket[] inWindow() {
        long nowSecond = clockMillis.getAsLong() / 1000;
        long oldest = nowSecond - windowSeconds + 1;
        Bucket[] out = new Bucket[windowSeconds];
        int found = 0;
        for (Bucket bucket : buckets) {
            long second = bucket.second.get();
            if (second >= oldest && second <= nowSecond) {
                out[found++] = bucket;
            }
        }
        if (found == windowSeconds) {
            return out;
        }
        Bucket[] trimmed = new Bucket[found];
        System.arraycopy(out, 0, trimmed, 0, found);
        return trimmed;
    }

    private static final class Bucket {
        private final AtomicLong second = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong latencySum = new AtomicLong();
        private final AtomicLong latencyCount = new AtomicLong();
    }
}
