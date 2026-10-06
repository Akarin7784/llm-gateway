package com.example.llmgw.api;

import com.example.llmgw.auth.ApiKeyAuthFilter;
import com.example.llmgw.auth.Tenant;
import com.example.llmgw.cache.CachedResponse;
import com.example.llmgw.cache.CachedResponseCodec;
import com.example.llmgw.cache.CacheKeyFactory;
import com.example.llmgw.cache.ResponseCache;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.example.llmgw.protocol.UpstreamResult;
import com.example.llmgw.protocol.Usage;
import com.example.llmgw.ratelimit.QuotaReservation;
import com.example.llmgw.ratelimit.QuotaService;
import com.example.llmgw.router.ModelRouter;
import com.example.llmgw.upstream.StreamAbortHandle;
import com.example.llmgw.upstream.StreamCallback;
import com.example.llmgw.upstream.UpstreamException;
import com.example.llmgw.upstream.UpstreamRegistry;
import com.example.llmgw.upstream.UpstreamTarget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ModelRouter router;
    private final UpstreamRegistry adapters;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;
    private final GatewayProperties properties;
    private final ExecutorService streamWorkers;
    private final ScheduledExecutorService heartbeats;
    private final QuotaService quota;
    private final ResponseCache cache;
    private final CacheKeyFactory cacheKeys;
    private final CachedResponseCodec cacheCodec;

    public ChatCompletionController(ModelRouter router, UpstreamRegistry adapters, GatewayMetrics metrics,
                                   ObjectMapper mapper, GatewayProperties properties,
                                   ExecutorService streamWorkers, ScheduledExecutorService heartbeats,
                                   QuotaService quota, ResponseCache cache, CacheKeyFactory cacheKeys,
                                   CachedResponseCodec cacheCodec) {
        this.router = router;
        this.adapters = adapters;
        this.metrics = metrics;
        this.mapper = mapper;
        this.properties = properties;
        this.streamWorkers = streamWorkers;
        this.heartbeats = heartbeats;
        this.quota = quota;
        this.cache = cache;
        this.cacheKeys = cacheKeys;
        this.cacheCodec = cacheCodec;
    }

    @PostMapping("/chat/completions")
    public Object completions(@RequestBody JsonNode raw, HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {
        Tenant tenant = (Tenant) servletRequest.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE);
        String requestId = (String) servletRequest.getAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE);
        ChatCompletionRequest request = canonicalize(raw);
        List<UpstreamTarget> candidates = router.candidates(request.model());

        Optional<String> cacheKey = cacheKeys.keyFor(tenant, raw, request);
        Optional<CachedResponse> hit = cacheKey.flatMap(cache::get);
        metrics.cacheLookup(tenant.id(), request.model(), cacheOutcome(cacheKey, hit));
        if (cacheKey.isPresent() && hit.isEmpty()) {
            servletResponse.setHeader("X-Cache", "MISS");
        }

        QuotaReservation reservation = quota.admit(tenant, request, requestId, hit.isPresent());
        if (hit.isPresent()) {
            metrics.cacheSaved(tenant.id(), request.model(), hit.get().usage());
            quota.release(reservation);
            servletResponse.setHeader("X-Cache", "HIT");
            if (request.stream()) {
                return replay(tenant, requestId, hit.get(), reservation);
            }
            return ResponseEntity.ok()
                    .header("X-Request-Id", requestId)
                    .header("X-Upstream", "cache")
                    .body(cacheCodec.toCompletionJson(hit.get()));
        }

        if (request.stream()) {
            return stream(tenant, requestId, raw, request, candidates, reservation, cacheKey);
        }
        try {
            ResponseEntity<JsonNode> response = complete(tenant, requestId, raw, request, candidates,
                    reservation, cacheKey);
            quota.release(reservation);
            return response;
        } catch (RuntimeException e) {
            quota.settleUnreconciled(reservation);
            quota.release(reservation);
            throw e;
        }
    }

    private static String cacheOutcome(Optional<String> key, Optional<CachedResponse> hit) {
        if (key.isEmpty()) {
            return "bypassed";
        }
        return hit.isPresent() ? "hit" : "miss";
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (String model : router.servedModels()) {
            views.add(Map.of("id", model, "object", "model", "owned_by", "llm-gateway"));
        }
        return Map.of("object", "list", "data", views);
    }

    /** Live breaker and window state, so routing decisions can be explained rather than guessed at. */
    @GetMapping("/routing")
    public Map<String, Object> routing() {
        return router.inspect();
    }

    private ResponseEntity<JsonNode> complete(Tenant tenant, String requestId, JsonNode raw,
                                             ChatCompletionRequest request, List<UpstreamTarget> candidates,
                                             QuotaReservation reservation, Optional<String> cacheKey) {
        long startedAt = System.nanoTime();
        for (int i = 0; i < candidates.size(); i++) {
            UpstreamTarget target = candidates.get(i);
            if (!router.tryReserve(target)) {
                continue;
            }
            try {
                UpstreamResult result = adapters.require(target.vendor()).complete(target, raw);
                metrics.attempt(tenant.id(), request.model(), target.name(), "success");
                metrics.upstreamLatency(target.name(), request.model(), result.upstreamMillis());
                metrics.endToEnd(tenant.id(), request.model(), millisSince(startedAt));
                metrics.tokens(tenant.id(), request.model(), result.usage());
                router.recordSuccess(target, result.upstreamMillis());
                quota.settle(reservation, result.usage());
                if (cacheKey.isPresent()) {
                    cacheCodec.fromVendor(result.body(), request.model(), result.upstreamMillis())
                            .ifPresent(value -> cache.put(cacheKey.get(), value));
                }
                return ResponseEntity.ok()
                        .header("X-Request-Id", requestId)
                        .header("X-Upstream", target.name())
                        .body(result.body());
            } catch (UpstreamException e) {
                router.recordFailure(target);
                if (canDegrade(e, i, candidates)) {
                    metrics.attempt(tenant.id(), request.model(), target.name(), "degraded");
                    metrics.degradation(target.name(), candidates.get(i + 1).name());
                    continue;
                }
                metrics.attempt(tenant.id(), request.model(), target.name(), "error");
                throw GatewayException.upstreamFailure(summarize(e), e);
            }
        }
        throw GatewayException.upstreamFailure("no upstream available for " + request.model(), null);
    }

    private SseEmitter stream(Tenant tenant, String requestId, JsonNode raw, ChatCompletionRequest request,
                              List<UpstreamTarget> candidates, QuotaReservation reservation,
                              Optional<String> cacheKey) {
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeout().toMillis());
        SseWriter writer = new SseWriter(emitter);
        StreamAbortHandle abortHandle = new StreamAbortHandle();
        AtomicReference<Future<?>> worker = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable cancelHeartbeat = () -> {
            ScheduledFuture<?> tick = heartbeat.get();
            if (tick != null) {
                tick.cancel(false);
            }
        };
        Runnable abort = () -> {
            // Closing the body stream is what breaks the vendor connection; the interrupt only makes
            // the pump leave its blocking read.
            abortHandle.abort();
            Future<?> future = worker.get();
            if (future != null) {
                future.cancel(true);
            }
            cancelHeartbeat.run();
        };
        emitter.onTimeout(abort);
        emitter.onError(error -> abort.run());
        emitter.onCompletion(cancelHeartbeat);

        // A dead peer is only observable on the next write. If the vendor stays silent that write may
        // be seconds away, so the heartbeat forces one: without it an abandoned client keeps its
        // upstream generation alive for as long as the vendor is quiet.
        long intervalMillis = properties.getStreamHeartbeatInterval().toMillis();
        if (intervalMillis > 0) {
            heartbeat.set(heartbeats.scheduleAtFixedRate(() -> {
                try {
                    writer.heartbeat();
                } catch (ClientDisconnectedException e) {
                    metrics.abandonedStream("heartbeat", "downstream_closed");
                    abort.run();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS));
        }

        worker.set(streamWorkers.submit(
                () -> pump(writer, abortHandle, reservation, tenant, requestId, raw, request, candidates,
                        cacheKey)));
        return emitter;
    }

    /** A cached answer is replayed through the same chunk envelope so streaming clients cannot tell it apart. */
    private SseEmitter replay(Tenant tenant, String requestId, CachedResponse cached, QuotaReservation reservation) {
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeout().toMillis());
        SseWriter writer = new SseWriter(emitter);
        long created = System.currentTimeMillis() / 1000;
        String chunkId = "chatcmpl-" + requestId;
        streamWorkers.submit(() -> {
            try {
                writer.data(chunk(chunkId, created, cached.model(), Map.of("content", cached.content()),
                        null, null, false));
                writer.data(chunk(chunkId, created, cached.model(), new LinkedHashMap<>(),
                        cached.finishReason() == null ? "stop" : cached.finishReason(), cached.usage(), true));
                writer.data("[DONE]");
            } catch (ClientDisconnectedException e) {
                metrics.abandonedStream("cache", "downstream_closed");
            } finally {
                writer.completeQuietly();
                quota.release(reservation);
            }
        });
        return emitter;
    }

    private void pump(SseWriter writer, StreamAbortHandle abortHandle, QuotaReservation reservation,
                      Tenant tenant, String requestId, JsonNode raw,
                      ChatCompletionRequest request, List<UpstreamTarget> candidates, Optional<String> cacheKey) {
        long startedAt = System.nanoTime();
        String chunkId = "chatcmpl-" + requestId;
        long created = System.currentTimeMillis() / 1000;

        try {
            for (int i = 0; i < candidates.size(); i++) {
                if (writer.closed()) {
                    return;
                }
                UpstreamTarget target = candidates.get(i);
                if (!router.tryReserve(target)) {
                    continue;
                }
                AtomicBoolean emittedToClient = new AtomicBoolean();
                AtomicInteger forwardedChars = new AtomicInteger();
                AtomicReference<Usage> reportedUsage = new AtomicReference<>(Usage.ZERO);
                AtomicReference<String> reportedFinish = new AtomicReference<>();
                StringBuilder forCache = cacheKey.isPresent() ? new StringBuilder() : null;
                try {
                    adapters.require(target.vendor()).stream(target, raw, new StreamCallback() {
                        @Override
                        public void onFirstToken() {
                            metrics.timeToFirstToken(tenant.id(), target.name(), millisSince(startedAt));
                        }

                        @Override
                        public void onDelta(String text) {
                            emittedToClient.set(true);
                            forwardedChars.addAndGet(text.length());
                            if (forCache != null) {
                                forCache.append(text);
                            }
                            writer.data(chunk(chunkId, created, request.model(), Map.of("content", text),
                                    null, null, false));
                        }

                        @Override
                        public void onCompleted(Usage usage, String finishReason) {
                            reportedUsage.set(usage);
                            reportedFinish.set(finishReason);
                            writer.data(chunk(chunkId, created, request.model(), new LinkedHashMap<>(),
                                    finishReason == null ? "stop" : finishReason, usage, true));
                        }
                    }, abortHandle);
                    writer.data("[DONE]");
                    long elapsed = millisSince(startedAt);
                    metrics.attempt(tenant.id(), request.model(), target.name(), "success");
                    metrics.endToEnd(tenant.id(), request.model(), elapsed);
                    metrics.tokens(tenant.id(), request.model(), reportedUsage.get());
                    router.recordSuccess(target, elapsed);
                    quota.settle(reservation, reportedUsage.get());
                    // Only a stream that ran to its end is worth caching; an abandoned one would freeze
                    // a partial answer under the key for the whole TTL.
                    if (forCache != null) {
                        cacheCodec.fromStream(forCache.toString(), reportedFinish.get(),
                                        reportedUsage.get(), request.model(), millisSince(startedAt))
                                .ifPresent(value -> cache.put(cacheKey.get(), value));
                    }
                    writer.completeQuietly();
                    return;
                } catch (ClientDisconnectedException e) {
                    // Our client left; the vendor did something wrong in neither case, so health stays
                    // untouched -- charging abandoned streams to the upstream would open a breaker
                    // during a browser refresh storm.
                    metrics.attempt(tenant.id(), request.model(), target.name(), "abandoned");
                    metrics.abandonedStream(target.name(), "downstream_closed");
                    // No usage report ever arrived, but the characters already forwarded are the best
                    // available proxy: releasing the whole reservation would make abandonment free.
                    quota.settle(reservation, Usage.of((int) reservation.estimatedPromptTokens(),
                            forwardedChars.get() / 4));
                    writer.completeQuietly();
                    return;
                } catch (UpstreamException e) {
                    router.recordFailure(target);
                    // Partial output already reached the client; retrying would duplicate text mid-sentence.
                    boolean midGeneration = emittedToClient.get() && e.kind() != UpstreamException.Kind.TIMEOUT;
                    if (!canDegrade(e, i, candidates) || midGeneration) {
                        metrics.attempt(tenant.id(), request.model(), target.name(), "error");
                        writeQuietly(writer, errorChunk(summarize(e)));
                        quota.settleUnreconciled(reservation);
                        writer.completeQuietly();
                        return;
                    }
                    metrics.attempt(tenant.id(), request.model(), target.name(), "degraded");
                    metrics.degradation(target.name(), candidates.get(i + 1).name());
                }
            }
        } finally {
            quota.release(reservation);
        }
    }

    private static boolean canDegrade(UpstreamException e, int index, List<UpstreamTarget> candidates) {
        return e.retryable() && index < candidates.size() - 1;
    }

    private ChatCompletionRequest canonicalize(JsonNode raw) {
        ChatCompletionRequest request;
        try {
            request = mapper.treeToValue(raw, ChatCompletionRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw GatewayException.badRequest("malformed chat completion request: " + e.getMessage());
        }
        if (request.model() == null || request.model().isBlank()) {
            throw GatewayException.badRequest("'model' is required");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw GatewayException.badRequest("'messages' must not be empty");
        }
        return request;
    }

    private String chunk(String id, long created, String model, Map<String, Object> delta, String finishReason,
                         Usage usage, boolean includeUsage) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("object", "chat.completion.chunk");
        payload.put("created", created);
        payload.put("model", model);
        payload.put("choices", List.of(choice));
        if (includeUsage && usage != null && usage.totalTokens() > 0) {
            Map<String, Object> usageView = new LinkedHashMap<>();
            usageView.put("prompt_tokens", usage.promptTokens());
            usageView.put("completion_tokens", usage.completionTokens());
            usageView.put("total_tokens", usage.totalTokens());
            payload.put("usage", usageView);
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise stream chunk", e);
        }
    }

    private String errorChunk(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", Map.of(
                    "message", message,
                    "type", "api_error",
                    "code", "upstream_failure")));
        } catch (JsonProcessingException e) {
            return "{\"error\":{\"message\":\"upstream failure\"}}";
        }
    }

    private void writeQuietly(SseWriter writer, String data) {
        try {
            writer.data(data);
        } catch (ClientDisconnectedException ignored) {
            // The peer is already gone; nothing useful left to report.
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static String summarize(UpstreamException e) {
        return "upstream '" + e.upstream() + "' failed (" + e.kind() + "): " + e.getMessage();
    }
}
