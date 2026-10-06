package com.example.llmgw.obs;

import com.example.llmgw.protocol.Usage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The numbers the gateway is actually judged on: time-to-first-token, the gap between end-to-end and
 * upstream latency (which is the gateway's own overhead), abandoned streams, and token spend per
 * tenant. Micrometer interns meters by name and tags, so call sites stay cheap.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void attempt(String tenant, String model, String upstream, String outcome) {
        registry.counter("gateway.requests", "tenant", tenant, "model", model,
                "upstream", upstream, "outcome", outcome).increment();
    }

    public void timeToFirstToken(String tenant, String upstream, long millis) {
        registry.summary("gateway.ttft.millis", "tenant", tenant, "upstream", upstream).record(millis);
    }

    public void upstreamLatency(String upstream, String model, long millis) {
        Timer.builder("gateway.upstream.millis").tag("upstream", upstream).tag("model", model)
                .register(registry).record(millis, TimeUnit.MILLISECONDS);
    }

    public void endToEnd(String tenant, String model, long millis) {
        Timer.builder("gateway.e2e.millis").tag("tenant", tenant).tag("model", model)
                .register(registry).record(millis, TimeUnit.MILLISECONDS);
    }

    public void abandonedStream(String upstream, String stage) {
        registry.counter("gateway.stream.abandoned", "upstream", upstream, "stage", stage).increment();
    }

    public void degradation(String fromUpstream, String toUpstream) {
        registry.counter("gateway.degradation", "from", fromUpstream, "to", toUpstream).increment();
    }

    public void tokens(String tenant, String model, Usage usage) {
        if (usage == null || usage.totalTokens() == 0) {
            return;
        }
        count("gateway.tokens.prompt", tenant, model, usage.promptTokens());
        count("gateway.tokens.completion", tenant, model, usage.completionTokens());
    }

    public void quotaAdmitted(String tenant, long reservedTokens) {
        registry.counter("gateway.quota.admitted", "tenant", tenant).increment();
        registry.counter("gateway.quota.reserved.tokens", "tenant", tenant).increment(reservedTokens);
    }

    public void quotaRejected(String tenant, String dimension) {
        registry.counter("gateway.quota.rejected", "tenant", tenant, "dimension", dimension).increment();
    }

    public void quotaRefunded(String tenant, long tokens) {
        registry.counter("gateway.quota.refunded.tokens", "tenant", tenant).increment(tokens);
    }

    /** Reserved less than the vendor actually produced: the estimate was too low. */
    public void quotaOverage(String tenant, long tokens) {
        registry.counter("gateway.quota.overage.tokens", "tenant", tenant).increment(tokens);
    }

    /** Settled from bytes forwarded rather than a vendor usage report. */
    public void quotaUnreconciled(String tenant, long tokens) {
        registry.counter("gateway.quota.unreconciled.tokens", "tenant", tenant).increment(tokens);
    }

    public void quotaBackendDegraded(String component) {
        registry.counter("gateway.quota.backend.degraded", "component", component).increment();
    }

    /** hit / miss / bypassed -- bypassed means the request was never eligible, so it must not be
     * counted as a miss or the hit ratio will look better than the cache actually is. */
    public void cacheLookup(String tenant, String model, String outcome) {
        registry.counter("gateway.cache.lookup", "tenant", tenant, "model", model, "outcome", outcome).increment();
    }

    public void cacheSaved(String tenant, String model, Usage usage) {
        if (usage == null || usage.totalTokens() == 0) {
            return;
        }
        registry.counter("gateway.cache.saved.tokens", "tenant", tenant, "model", model)
                .increment(usage.totalTokens());
    }

    public void upstreamProbeResult(String upstream, String outcome) {
        registry.counter("gateway.circuit.probe", "upstream", upstream, "outcome", outcome).increment();
    }

    public void circuitState(String upstream, String previous, String next) {
        registry.counter("gateway.circuit.transition", "upstream", upstream,
                "from", previous, "to", next).increment();
    }

    public void routingScore(String winner, String runnerUp) {
        registry.counter("gateway.route.selected", "upstream", winner, "runner_up", String.valueOf(runnerUp))
                .increment();
    }

    /** Every candidate was open and traffic went out anyway. */
    public void forcedRouting(String model) {
        registry.counter("gateway.route.forced", "model", model).increment();
    }

    public void ledgerWritten(int rows, long batchMillis) {
        registry.counter("gateway.ledger.written", "outcome", "inserted").increment(rows);
        registry.timer("gateway.ledger.batch.millis").record(batchMillis, TimeUnit.MILLISECONDS);
    }

    /** The queue was full: the entry went to the spill file rather than being silently dropped. */
    public void ledgerSpilled(String reason, int rows) {
        registry.counter("gateway.ledger.spilled", "reason", reason).increment(rows);
    }

    public void ledgerWriteFailed() {
        registry.counter("gateway.ledger.write_failed").increment();
    }

    public void ledgerReplayed(int rows) {
        registry.counter("gateway.ledger.replayed").increment(rows);
    }

    public void ledgerLost(int rows) {
        registry.counter("gateway.ledger.lost").increment(rows);
    }

    private void count(String name, String tenant, String model, long amount) {
        registry.counter(name, "tenant", tenant, "model", model).increment(amount);
    }
}
