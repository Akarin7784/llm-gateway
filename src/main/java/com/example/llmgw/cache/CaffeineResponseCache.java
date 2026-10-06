package com.example.llmgw.cache;

import com.example.llmgw.config.GatewayProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Size-bounded with a write TTL: LRU alone would let a stale answer live forever under low traffic,
 * and an unbounded map turns a cache hit ratio into an OOM risk.
 */
@Component
public class CaffeineResponseCache implements ResponseCache {

    private final Cache<String, CachedResponse> store;
    private final boolean enabled;

    public CaffeineResponseCache(GatewayProperties properties, MeterRegistry registry) {
        GatewayProperties.Cache config = properties.getCache();
        this.enabled = config.isEnabled();
        this.store = Caffeine.newBuilder()
                .maximumSize(config.getMaxEntries())
                .expireAfterWrite(config.getTtl())
                .recordStats()
                .build();
        // Caffeine's Cache is also a ConcurrentMap, which makes Micrometer's gauge overloads ambiguous:
        // the explicit ToDoubleFunction picks the numeric one rather than "size of a map".
        registry.gauge("gateway.cache.entries", store,
                (ToDoubleFunction<Cache<String, CachedResponse>>) cache -> cache.estimatedSize());
        registry.gauge("gateway.cache.hit_ratio", store,
                (ToDoubleFunction<Cache<String, CachedResponse>>) cache -> hitRate(cache.stats()));
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.getIfPresent(key));
    }

    @Override
    public void put(String key, CachedResponse response) {
        if (enabled) {
            store.put(key, response);
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    private static double hitRate(CacheStats stats) {
        long lookups = stats.requestCount();
        return lookups == 0 ? 0 : (double) stats.hitCount() / lookups;
    }
}
