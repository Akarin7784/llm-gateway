package com.example.llmgw.ratelimit;

import java.util.concurrent.atomic.AtomicBoolean;

/** What admission took on a tenant's account, and the handles needed to give it back. */
public final class QuotaReservation {

    private final boolean enforced;
    private final String tenantId;
    private final String concurrencyKey;
    private final String tokenKey;
    private final String leaseId;
    private final long estimatedTokens;
    private final long estimatedPromptTokens;
    private final boolean tokenReserved;
    private final BucketConfig tokenConfig;
    private final AtomicBoolean settled = new AtomicBoolean();

    private QuotaReservation(boolean enforced, String tenantId, String concurrencyKey, String tokenKey,
                             String leaseId, long estimatedTokens, long estimatedPromptTokens,
                             boolean tokenReserved, BucketConfig tokenConfig) {
        this.enforced = enforced;
        this.tenantId = tenantId;
        this.concurrencyKey = concurrencyKey;
        this.tokenKey = tokenKey;
        this.leaseId = leaseId;
        this.estimatedTokens = estimatedTokens;
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.tokenReserved = tokenReserved;
        this.tokenConfig = tokenConfig;
    }

    static QuotaReservation enforced(String tenantId, String concurrencyKey, String tokenKey, String leaseId,
                                     long estimatedTokens, long estimatedPromptTokens, boolean tokenReserved,
                                     BucketConfig tokenConfig) {
        return new QuotaReservation(true, tenantId, concurrencyKey, tokenKey, leaseId, estimatedTokens,
                estimatedPromptTokens, tokenReserved, tokenConfig);
    }

    static QuotaReservation unenforced(String tenantId, String leaseId) {
        return new QuotaReservation(false, tenantId, null, null, leaseId, 0, 0, false, null);
    }

    public boolean enforced() {
        return enforced;
    }

    public String tenantId() {
        return tenantId;
    }

    public String concurrencyKey() {
        return concurrencyKey;
    }

    public String tokenKey() {
        return tokenKey;
    }

    public String leaseId() {
        return leaseId;
    }

    public long estimatedTokens() {
        return estimatedTokens;
    }

    public long estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    /** False for a cache hit: nothing was spent, so there is nothing to settle or refund. */
    public boolean tokenReserved() {
        return tokenReserved;
    }

    public BucketConfig tokenConfig() {
        return tokenConfig;
    }

    /** First caller wins, so a settle on the success path and a finally-block release cannot double-pay. */
    boolean markSettled() {
        return settled.compareAndSet(false, true);
    }
}
