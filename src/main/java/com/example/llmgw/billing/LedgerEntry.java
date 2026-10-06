package com.example.llmgw.billing;

import java.time.Instant;

/** One settled exchange. Cost is in whole micro-units; see schema.sql for why. */
public record LedgerEntry(
        String requestId,
        String tenantId,
        String model,
        String upstream,
        String outcome,
        int promptTokens,
        int completionTokens,
        long costMicros,
        long latencyMillis,
        Long ttftMillis,
        boolean cached,
        Instant createdAt) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
