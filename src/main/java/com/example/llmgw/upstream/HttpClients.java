package com.example.llmgw.upstream;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link HttpClient} owns a selector thread and a connection pool, so instances are shared instead
 * of being built per request. Keying by connect timeout keeps the pool per target configuration
 * rather than per call.
 */
final class HttpClients {

    private static final ConcurrentHashMap<Duration, HttpClient> CACHE = new ConcurrentHashMap<>();

    private HttpClients() {
    }

    static HttpClient forConnectTimeout(Duration connectTimeout) {
        return CACHE.computeIfAbsent(connectTimeout, timeout -> HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }
}
