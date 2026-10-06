package com.example.llmgw.ratelimit;

/**
 * The arithmetic half of the token bucket, kept free of storage so the Java and the Lua
 * implementations can be checked against one specification. The Lua script mirrors this exactly;
 * what differs between the two is only where the state lives and what makes the update atomic.
 */
public final class TokenBucketMath {

    private TokenBucketMath() {
    }

    /** Tokens available at {@code nowMillis}, refilled linearly and clamped to burst capacity. */
    public static double refill(double tokens, long lastRefillMillis, long nowMillis, BucketConfig config) {
        if (nowMillis <= lastRefillMillis) {
            return Math.min(tokens, config.capacity());
        }
        double elapsedSeconds = (nowMillis - lastRefillMillis) / 1000.0;
        return Math.min(config.capacity(), tokens + elapsedSeconds * config.refillPerSecond());
    }

    /** Milliseconds until {@code permits} would fit, used for the Retry-After header. */
    public static long millisUntilAvailable(double tokens, double permits, BucketConfig config) {
        double missing = permits - tokens;
        if (missing <= 0) {
            return 0;
        }
        return (long) Math.ceil(missing / config.refillPerSecond() * 1000.0);
    }
}
