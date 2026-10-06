package com.example.llmgw.config;

import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.ratelimit.ConcurrencyGate;
import com.example.llmgw.ratelimit.InMemoryConcurrencyGate;
import com.example.llmgw.ratelimit.InMemoryTokenBucket;
import com.example.llmgw.ratelimit.RedisConcurrencyGate;
import com.example.llmgw.ratelimit.RedisTokenBucket;
import com.example.llmgw.ratelimit.ResilientConcurrencyGate;
import com.example.llmgw.ratelimit.ResilientTokenBucket;
import com.example.llmgw.ratelimit.TokenBucket;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chooses the quota backends once at startup. The in-memory implementations are always present and
 * act as the fallback; the Redis ones only exist when {@code gateway.quota.distributed=true}.
 */
@Configuration(proxyBeanMethods = false)
public class QuotaConfiguration {

    @Bean
    @Primary
    TokenBucket quotaTokenBucket(InMemoryTokenBucket inMemory, ObjectProvider<RedisTokenBucket> redis,
                                 GatewayMetrics metrics, GatewayProperties properties) {
        RedisTokenBucket distributed = redis.getIfAvailable();
        if (distributed == null || !properties.getQuota().isDistributed()) {
            return inMemory;
        }
        return new ResilientTokenBucket(distributed, inMemory, () -> metrics.quotaBackendDegraded("token-bucket"));
    }

    @Bean
    @Primary
    ConcurrencyGate quotaConcurrencyGate(InMemoryConcurrencyGate inMemory, ObjectProvider<RedisConcurrencyGate> redis,
                                         GatewayMetrics metrics, GatewayProperties properties) {
        RedisConcurrencyGate distributed = redis.getIfAvailable();
        if (distributed == null || !properties.getQuota().isDistributed()) {
            return inMemory;
        }
        return new ResilientConcurrencyGate(distributed, inMemory,
                () -> metrics.quotaBackendDegraded("concurrency-gate"));
    }
}
