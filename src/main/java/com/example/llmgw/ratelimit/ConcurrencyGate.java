package com.example.llmgw.ratelimit;

import java.time.Duration;

public interface ConcurrencyGate {

    /**
     * Takes one in-flight slot identified by {@code leaseId}.
     *
     * The lease is time-bounded rather than purely counted on purpose: a gateway replica that dies
     * mid-stream cannot run its release, so an unbounded counter would leak slots permanently and
     * strangle the tenant. Expiry is the reclamation path.
     */
    boolean tryAcquire(String key, String leaseId, int limit, Duration leaseTtl);

    void release(String key, String leaseId);
}
