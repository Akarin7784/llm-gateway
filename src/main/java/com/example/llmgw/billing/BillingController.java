package com.example.llmgw.billing;

import com.example.llmgw.auth.ApiKeyAuthFilter;
import com.example.llmgw.auth.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spend reporting plus the reconciliation view that makes the ledger falsifiable.
 *
 * Two equalities are asserted, and only two: tokens on rows that really executed at a vendor must equal
 * the token counters the request path incremented, and tokens on cached rows must equal the "saved"
 * counter. They are different questions -- a cached answer saved spend rather than spent it, and an
 * abandoned stream is priced from forwarded bytes rather than from a vendor report -- so collapsing
 * everything into one "delta must be zero" check would either fail spuriously or be tuned until it
 * proves nothing.
 */
@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final LedgerStore store;
    private final MeterRegistry registry;
    private final java.time.Instant processStart = java.time.Instant.now();

    public BillingController(LedgerStore store, MeterRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request) {
        Tenant tenant = (Tenant) request.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE);
        Map<String, Map<String, Object>> spend = store.spendByTenant();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tenant", tenant.id());
        view.put("this_tenant", spend.getOrDefault(tenant.id(), Map.of()));
        view.put("by_outcome", store.rowsByOutcome());
        view.put("all_tenants", spend);
        return view;
    }

    @GetMapping("/reconcile")
    public Map<String, Object> reconcile() {
        // Every figure below is scoped to this process so the equations survive restarts: the ledger is
        // durable, the counters are not.
        Map<String, Map<String, Object>> byOutcome = store.rowsByOutcomeSince(processStart);
        long ledgerRowsThisProcess = byOutcome.values().stream()
                .mapToLong(row -> ((Number) row.get("rows")).longValue())
                .sum();

        long ledgerExecuted = outcomeTokens(byOutcome, "success");
        long ledgerCached = outcomeTokens(byOutcome, "cached");
        long countedExecuted = counterValue("gateway.tokens.prompt") + counterValue("gateway.tokens.completion");
        long countedSaved = counterValue("gateway.cache.saved.tokens");
        long rowsTheWriterReports = counterValue("gateway.ledger.written") + counterValue("gateway.ledger.replayed");

        Map<String, Object> equations = new LinkedHashMap<>();
        equations.put("executed_tokens", Map.of(
                "ledger", ledgerExecuted,
                "counters", countedExecuted,
                "delta", ledgerExecuted - countedExecuted));
        equations.put("cached_tokens", Map.of(
                "ledger", ledgerCached,
                "counters", countedSaved,
                "delta", ledgerCached - countedSaved));
        // Only closes when the write queue has drained; queue_depth is reported right below it so a
        // transient difference cannot be mistaken for a lost row.
        equations.put("rows_this_process", Map.of(
                "ledger", ledgerRowsThisProcess,
                "counters", rowsTheWriterReports,
                "delta", ledgerRowsThisProcess - rowsTheWriterReports));

        Map<String, Object> inferred = new LinkedHashMap<>();
        inferred.put("abandoned_tokens", outcomeTokens(byOutcome, "abandoned"));
        inferred.put("error_tokens", outcomeTokens(byOutcome, "error"));
        inferred.put("note", "priced from forwarded bytes: the vendor usage chunk never arrived");

        Map<String, Object> durability = new LinkedHashMap<>();
        durability.put("queue_depth", gaugeValue("gateway.ledger.queue_depth"));
        durability.put("spilled_rows", counterValue("gateway.ledger.spilled"));
        durability.put("lost_rows", counterValue("gateway.ledger.lost"));
        durability.put("replayed_rows", counterValue("gateway.ledger.replayed"));
        durability.put("written_rows", counterValue("gateway.ledger.written"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "equations cover rows written since this process started at " + processStart
                + "; the ledger itself is durable and holds earlier processes' traffic.");
        result.put("equations", equations);
        result.put("inferred_pricing", inferred);
        result.put("durability", durability);
        result.put("integrity", store.integrityProbe());
        result.put("by_outcome", byOutcome);
        return result;
    }

    private static long outcomeTokens(Map<String, Map<String, Object>> byOutcome, String outcome) {
        Map<String, Object> row = byOutcome.get(outcome);
        return row == null ? 0 : ((Number) row.get("tokens")).longValue();
    }

    private long counterValue(String name) {
        return registry.find(name).counters().stream()
                .mapToLong(counter -> (long) counter.count())
                .sum();
    }

    private double gaugeValue(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
