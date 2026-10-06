package com.example.llmgw.cache;

import com.example.llmgw.protocol.Usage;

/**
 * Stored as fields rather than as the vendor's raw JSON so replay can rewrite {@code id} and
 * {@code created} per response, and so a cached answer can be served to a streaming client that
 * never saw the original transport.
 */
public record CachedResponse(String model, String content, String finishReason, Usage usage, long storedAtMillis,
                             long upstreamMillis) {
}
