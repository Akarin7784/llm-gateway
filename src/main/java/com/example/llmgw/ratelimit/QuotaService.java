package com.example.llmgw.ratelimit;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.auth.Tenant;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.example.llmgw.protocol.Usage;
import org.springframework.stereotype.Service;

@Service
public class QuotaService {

    private final TokenBucket requestBucket;
    private final TokenBucket tokenBucket;
    private final ConcurrencyGate gate;
    private final TokenEstimator estimator;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;

    public QuotaService(TokenBucket bucket, ConcurrencyGate gate, TokenEstimator estimator,
                        GatewayMetrics metrics, GatewayProperties properties) {
        // One bucket store serves both dimensions: state is keyed per tenant and per dimension, and
        // the shape of each limit arrives with the call.
        this.requestBucket = bucket;
        this.tokenBucket = bucket;
        this.gate = gate;
        this.estimator = estimator;
        this.metrics = metrics;
        this.properties = properties;
    }

    public QuotaReservation admit(Tenant tenant, ChatCompletionRequest request, String requestId,
                                  boolean cacheHit) {
        GatewayProperties.Quota quota = properties.getQuota();
        if (!quota.isEnabled()) {
            return QuotaReservation.unenforced(tenant.id(), requestId);
        }

        String concurrencyKey = "conc:" + tenant.id();
        if (!gate.tryAcquire(concurrencyKey, requestId, tenant.maxConcurrency(), quota.getLeaseTtl())) {
            metrics.quotaRejected(tenant.id(), "concurrency");
            throw GatewayException.rateLimited(
                    "in-flight limit (" + tenant.maxConcurrency() + ") reached", 250);
        }

        BucketConfig requestConfig = BucketConfig.of(tenant.rpm(), tenant.rpm());
        String requestKey = "rpm:" + tenant.id();
        TokenBucket.Outcome requests = requestBucket.tryTake(requestKey, 1, requestConfig);
        if (!requests.allowed()) {
            gate.release(concurrencyKey, requestId);
            metrics.quotaRejected(tenant.id(), "rpm");
            throw GatewayException.rateLimited(
                    "requests-per-minute limit (" + tenant.rpm() + ") exceeded", requests.retryAfterMillis());
        }

        if (cacheHit) {
            // A served-from-cache answer still costs a request, but reserving tokens for it would
            // make the token ceiling fictional -- and a high hit ratio would look like free headroom.
            metrics.quotaAdmitted(tenant.id(), 0);
            return QuotaReservation.enforced(tenant.id(), concurrencyKey, requestKey, requestId, 0, 0, false,
                    requestConfig);
        }

        long estimate = estimator.estimateTotal(request);
        long promptEstimate = estimator.estimatePrompt(request);
        BucketConfig tokenConfig = BucketConfig.of(tenant.tpm(), tenant.tpm());
        String tokenKey = "tpm:" + tenant.id();
        TokenBucket.Outcome tokens = tokenBucket.tryTake(tokenKey, estimate, tokenConfig);
        if (!tokens.allowed()) {
            requestBucket.refund(requestKey, 1, requestConfig);
            gate.release(concurrencyKey, requestId);
            metrics.quotaRejected(tenant.id(), "tpm");
            throw GatewayException.rateLimited("tokens-per-minute limit (" + tenant.tpm()
                    + ") exceeded, request reserves about " + estimate + " tokens", tokens.retryAfterMillis());
        }

        metrics.quotaAdmitted(tenant.id(), estimate);
        return QuotaReservation.enforced(tenant.id(), concurrencyKey, tokenKey, requestId, estimate,
                promptEstimate, true, tokenConfig);
    }

    /**
     * The exchange failed without a usage report. The reservation stays spent rather than being
     * refunded: an unrefundable failure is the only thing standing between a retry loop and free
     * spend, and the count is exported so the estimate can be tuned against reality.
     */
    public void settleUnreconciled(QuotaReservation reservation) {
        if (!reservation.enforced() || !reservation.tokenReserved() || !reservation.markSettled()) {
            return;
        }
        metrics.quotaUnreconciled(reservation.tenantId(), reservation.estimatedTokens());
    }

    /** Reconciles the reservation against what the vendor actually reported. */
    public void settle(QuotaReservation reservation, Usage actual) {
        if (!reservation.enforced() || !reservation.tokenReserved() || !reservation.markSettled()) {
            return;
        }
        long reclaimable = reservation.estimatedTokens() - actual.totalTokens();
        if (reclaimable > 0) {
            tokenBucket.refund(reservation.tokenKey(), reclaimable, reservation.tokenConfig());
            metrics.quotaRefunded(reservation.tenantId(), reclaimable);
        } else if (reclaimable < 0) {
            // The vendor produced more than reserved. Retro-approving that would defeat the ceiling,
            // so the shortfall is recorded and billed, and the estimator is what gets retuned.
            metrics.quotaOverage(reservation.tenantId(), -reclaimable);
        }
    }

    public void release(QuotaReservation reservation) {
        if (reservation.enforced()) {
            gate.release(reservation.concurrencyKey(), reservation.leaseId());
        }
    }
}
