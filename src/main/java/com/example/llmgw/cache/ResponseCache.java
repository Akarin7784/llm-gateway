package com.example.llmgw.cache;

import java.util.Optional;

public interface ResponseCache {

    Optional<CachedResponse> get(String key);

    void put(String key, CachedResponse response);

    boolean enabled();
}
