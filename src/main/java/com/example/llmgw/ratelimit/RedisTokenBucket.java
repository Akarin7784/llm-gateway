package com.example.llmgw.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared bucket across gateway replicas. Enabled only with {@code gateway.quota.distributed=true};
 * the scripts are single-key and hash-slot safe, so a Redis Cluster can be used unchanged.
 *
 * <p>Not exercised in this environment: no Redis is running locally, so the arithmetic is proven via
 * {@link TokenBucketMath} and the scripts are a transcription of it. Treat the wiring as the
 * unverified part, not the algorithm.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.quota", name = "distributed", havingValue = "true")
public class RedisTokenBucket implements TokenBucket {

    /** Idle tenants' buckets are not worth keeping; two refill windows is long enough to be exact. */
    private static final long BUCKET_TTL_SECONDS = 120;

    private final StringRedisTemplate redis;
    private final RedisScript<List> takeScript;
    private final RedisScript<List> refundScript;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
        this.takeScript = script("lua/token_bucket_take.lua");
        this.refundScript = script("lua/token_bucket_refund.lua");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Outcome tryTake(String key, long permits, BucketConfig config) {
        List<Object> reply = redis.execute(takeScript, List.of(prefixed(key)),
                String.valueOf(config.capacity()), String.valueOf(config.refillPerMinute()),
                String.valueOf(permits), String.valueOf(BUCKET_TTL_SECONDS));
        if (reply == null || reply.size() < 3) {
            throw new IllegalStateException("unexpected token bucket reply: " + reply);
        }
        boolean allowed = asLong(reply.get(0)) == 1;
        return new Outcome(allowed, asDouble(reply.get(1)), asLong(reply.get(2)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refund(String key, long permits, BucketConfig config) {
        redis.execute(refundScript, List.of(prefixed(key)),
                String.valueOf(config.capacity()), String.valueOf(config.refillPerMinute()),
                String.valueOf(permits), String.valueOf(BUCKET_TTL_SECONDS));
    }

    private static String prefixed(String key) {
        return "llmgw:bucket:" + key;
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<List> script(String path) {
        org.springframework.data.redis.core.script.DefaultRedisScript<List> script =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
