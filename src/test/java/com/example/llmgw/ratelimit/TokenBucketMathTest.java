package com.example.llmgw.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketMathTest {

    private static final BucketConfig BUCKET = BucketConfig.of(60, 60);

    @Test
    void startsFullAndStaysCapped() {
        assertThat(TokenBucketMath.refill(60, 0, 10_000, BUCKET)).isEqualTo(60.0);
        assertThat(TokenBucketMath.refill(10, 0, 10_000, BUCKET)).isLessThanOrEqualTo(60.0);
    }

    @Test
    void refillsLinearlyWithElapsedTime() {
        // 60 per minute is one token per second.
        assertThat(TokenBucketMath.refill(0, 1_000, 3_000, BUCKET)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void ignoresClockGoingBackwards() {
        assertThat(TokenBucketMath.refill(5, 10_000, 9_000, BUCKET)).isEqualTo(5.0);
    }

    @Test
    void retryAfterReflectsTheMissingAmount() {
        assertThat(TokenBucketMath.millisUntilAvailable(0, 10, BUCKET)).isEqualTo(10_000);
        assertThat(TokenBucketMath.millisUntilAvailable(50, 10, BUCKET)).isZero();
    }

    @Test
    void slowRefillMeansLongWaits() {
        BucketConfig slow = BucketConfig.of(100, 6);
        assertThat(TokenBucketMath.millisUntilAvailable(0, 1, slow)).isEqualTo(10_000);
    }
}
