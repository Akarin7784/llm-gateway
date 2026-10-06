package com.example.llmgw.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "gateway.quota", name = "distributed", havingValue = "true")
public class RedisConcurrencyGate implements ConcurrencyGate {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> acquireScript;

    public RedisConcurrencyGate(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/concurrency_lease_acquire.lua"));
        script.setResultType(Long.class);
        this.acquireScript = script;
    }

    @Override
    public boolean tryAcquire(String key, String leaseId, int limit, Duration leaseTtl) {
        Long granted = redis.execute(acquireScript, List.of(prefixed(key)),
                String.valueOf(limit), leaseId, String.valueOf(leaseTtl.toMillis()));
        return granted != null && granted == 1L;
    }

    @Override
    public void release(String key, String leaseId) {
        redis.opsForZSet().remove(prefixed(key), leaseId);
    }

    private static String prefixed(String key) {
        return "llmgw:concurrency:" + key;
    }
}
