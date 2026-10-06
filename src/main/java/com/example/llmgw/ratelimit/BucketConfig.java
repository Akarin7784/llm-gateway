package com.example.llmgw.ratelimit;

/**
 * A bucket is described by its burst capacity and how much it refills per minute, which is how the
 * tenant policy is written in configuration (rpm / tpm) rather than in abstract rates.
 */
public record BucketConfig(long capacity, long refillPerMinute) {

    public double refillPerSecond() {
        return refillPerMinute / 60.0;
    }

    public static BucketConfig of(long capacity, long refillPerMinute) {
        return new BucketConfig(Math.max(1, capacity), Math.max(1, refillPerMinute));
    }
}
