package com.example.llmgw.ratelimit;

public interface TokenBucket {

    /**
     * Atomic check-and-consume. Implementations must not read, decide and write in separate
     * round-trips: a burst of concurrent callers would otherwise all see a full bucket.
     */
    Outcome tryTake(String key, long permits, BucketConfig config);

    /** Returns unconsumed permits, used when the estimate at admission came in high. */
    void refund(String key, long permits, BucketConfig config);

    /**
     * @param allowed         whether the permits were taken
     * @param remaining       tokens left after the decision
     * @param retryAfterMillis how long a caller should wait before retrying
     */
    record Outcome(boolean allowed, double remaining, long retryAfterMillis) {

        static Outcome allow(double remaining) {
            return new Outcome(true, remaining, 0);
        }

        static Outcome deny(double remaining, long retryAfterMillis) {
            return new Outcome(false, remaining, retryAfterMillis);
        }
    }
}
