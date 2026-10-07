package com.example.llmgw.router;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.upstream.UpstreamTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Turns a logical model name into an ordered, still-worth-trying list of upstreams.
 *
 * Two separate questions are deliberately kept apart: {@link #candidates} asks which upstreams are
 * eligible and in what order, while {@link #tryReserve} spends a half-open probe slot. Reserving on
 * the first call would burn probe budget on upstreams the request never ends up using, which is
 * enough to keep a recovering vendor from ever completing its own health check.
 */
@Component
public class ModelRouter {

    private final Map<String, List<UpstreamTarget>> catalog = new LinkedHashMap<>();
    private final Map<String, RouteState> states = new ConcurrentHashMap<>();
    private final GatewayProperties.Routing config;
    private final GatewayMetrics metrics;

    public ModelRouter(GatewayProperties properties, GatewayMetrics metrics) {
        this.config = properties.getRouting();
        this.metrics = metrics;
        for (GatewayProperties.UpstreamConfig upstream : properties.getUpstreams()) {
            for (String model : upstream.getModels()) {
                catalog.computeIfAbsent(model, key -> new ArrayList<>())
                        .add(new UpstreamTarget(upstream.getName(), upstream.getVendor(),
                                upstream.getBaseUrl(), upstream.getApiKey(), model,
                                upstream.getConnectTimeout(), upstream.getFirstTokenTimeout(),
                                upstream.getReadTimeout(), upstream.getPriceInPer1k(),
                                upstream.getPriceOutPer1k()));
            }
            states.computeIfAbsent(upstream.getName(), name -> new RouteState(name, config, metrics));
        }
    }

    public List<UpstreamTarget> candidates(String model) {
        List<UpstreamTarget> configured = catalog.get(model);
        if (configured == null || configured.isEmpty()) {
            metrics.reject("model_not_found");
            throw GatewayException.modelNotFound(model);
        }

        List<UpstreamTarget> eligible = new ArrayList<>();
        for (UpstreamTarget target : configured) {
            if (state(target).available()) {
                eligible.add(target);
            }
        }
        if (eligible.isEmpty()) {
            // Every vendor is open. Refusing outright turns a partial outage into a total one, so let
            // traffic through in priority order and let the counters make it visible.
            metrics.forcedRouting(model);
            return configured;
        }

        double maxLatency = 0;
        double maxCost = 0;
        double[] latencies = new double[eligible.size()];
        double[] costs = new double[eligible.size()];
        double[] errors = new double[eligible.size()];
        boolean[] cold = new boolean[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            UpstreamTarget target = eligible.get(i);
            RouteState routeState = state(target);
            cold[i] = routeState.health.volume() == 0;
            latencies[i] = routeState.health.meanLatencyMillis();
            // One timeout is not an outage. Shrinking the error rate toward zero until the window holds
            // minVolume observations keeps a low-traffic vendor from being sentenced by a single sample,
            // which is what otherwise starves it of exactly the traffic it needs to trip its breaker.
            double confidence = Math.min(1.0, routeState.health.volume() / (double) config.getMinVolume());
            errors[i] = routeState.health.errorRate() * confidence;
            costs[i] = estimatedCost(target);
            maxLatency = Math.max(maxLatency, latencies[i]);
            maxCost = Math.max(maxCost, costs[i]);
        }

        List<Scored> scored = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            UpstreamTarget target = eligible.get(i);
            // Latency and cost are unbounded, so they are scaled against the best/worst candidate to
            // make their weights comparable. Error rate is already absolute in 0..1: normalising it by
            // the maximum would turn "one shrunk failure" and "permanently broken" into the same number,
            // which with only two candidates is the same as deleting the signal.
            double score = config.getLatencyWeight() * ratio(latencies[i], maxLatency)
                    + config.getErrorWeight() * errors[i]
                    + config.getCostWeight() * ratio(costs[i], maxCost);
            scored.add(new Scored(target, score, i, cold[i]));
        }
        // Unmeasured vendors are tried first, before anything ranked by evidence: a candidate that only
        // loses because it has no samples would otherwise never be sampled, stay cold forever, and its
        // breaker would never accumulate the volume needed to decide anything. The cost is paying real
        // traffic to warm up every vendor, which is cheaper than routing on a guess.
        scored.sort(Comparator.comparingInt((Scored entry) -> entry.cold() ? 0 : 1)
                .thenComparingDouble(Scored::score)
                .thenComparingInt(Scored::priority));
        if (scored.size() > 1) {
            metrics.routingScore(scored.get(0).target.name(), scored.get(1).target.name());
        }
        return scored.stream().map(Scored::target).toList();
    }

    /** Spends a probe slot if one is due; called immediately before an actual attempt. */
    public boolean tryReserve(UpstreamTarget target) {
        return state(target).breaker.allowRequest();
    }

    /** The breaker owns the window write: recording here as well would double-count every call. */
    public void recordSuccess(UpstreamTarget target, long latencyMillis) {
        state(target).breaker.onResponse(false, latencyMillis);
    }

    public void recordFailure(UpstreamTarget target) {
        state(target).breaker.onResponse(true, 0);
    }

    public Map<String, Object> inspect() {
        Map<String, Object> view = new LinkedHashMap<>();
        states.forEach((name, routeState) -> view.put(name, Map.of(
                "state", routeState.breaker.state().name(),
                "window_requests", routeState.health.volume(),
                "error_rate", round(routeState.health.errorRate()),
                "mean_latency_ms", round(routeState.health.meanLatencyMillis()))));
        return view;
    }

    public List<String> servedModels() {
        return List.copyOf(catalog.keySet());
    }

    private RouteState state(UpstreamTarget target) {
        RouteState routeState = states.get(target.name());
        if (routeState == null) {
            throw new IllegalStateException("upstream '" + target.name() + "' has no health state");
        }
        return routeState;
    }

    private double estimatedCost(UpstreamTarget target) {
        return target.priceInPer1k() / 1000.0 * config.getAssumedPromptTokens()
                + target.priceOutPer1k() / 1000.0 * config.getAssumedCompletionTokens();
    }

    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : value / max;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private record Scored(UpstreamTarget target, double score, int priority, boolean cold) {
    }

    private static final class RouteState {
        private final String name;
        private final UpstreamHealth health;
        private final CircuitBreaker breaker;

        RouteState(String name, GatewayProperties.Routing config, GatewayMetrics metrics) {
            this.name = name;
            // One clock for both: the window and the cooldown have to agree on what "now" means.
            LongSupplier clock = System::currentTimeMillis;
            this.health = new UpstreamHealth(config.getWindowSeconds(), clock);
            this.breaker = new CircuitBreaker(name, config, health, metrics, clock);
        }

        /** OPEN until the cooldown expires; CLOSED and HALF_OPEN are both worth attempting. */
        boolean available() {
            return !breaker.coolingDown();
        }
    }
}
