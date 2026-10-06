package com.example.llmgw.cache;

import com.example.llmgw.protocol.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Translates between a vendor payload and the shape the cache stores. */
@Component
public class CachedResponseCodec {

    private final ObjectMapper mapper;

    public CachedResponseCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<CachedResponse> fromVendor(JsonNode body, String model, long upstreamMillis) {
        JsonNode choice = body.path("choices").path(0);
        String content = choice.path("message").path("content").asText(null);
        if (content == null) {
            return Optional.empty();
        }
        String finishReason = choice.path("finish_reason").asText(null);
        if (!CacheKeyFactory.cacheableFinishReason(finishReason)) {
            return Optional.empty();
        }
        JsonNode usage = body.path("usage");
        Usage tokens = usage.isObject()
                ? new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0))
                : Usage.ZERO;
        return Optional.of(new CachedResponse(model, content, finishReason, tokens,
                System.currentTimeMillis(), upstreamMillis));
    }

    public Optional<CachedResponse> fromStream(String content, String finishReason, Usage usage, String model,
                                               long upstreamMillis) {
        if (content == null || !CacheKeyFactory.cacheableFinishReason(finishReason)) {
            return Optional.empty();
        }
        return Optional.of(new CachedResponse(model, content, finishReason, usage == null ? Usage.ZERO : usage,
                System.currentTimeMillis(), upstreamMillis));
    }

    public JsonNode toCompletionJson(CachedResponse cached) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "chatcmpl-cache-" + Long.toHexString(cached.storedAtMillis()));
        root.put("object", "chat.completion");
        root.put("created", cached.storedAtMillis() / 1000);
        root.put("model", cached.model());
        root.put("gateway_cache", "HIT");
        var choices = root.putArray("choices");
        var choice = choices.addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", cached.content());
        choice.put("finish_reason", cached.finishReason() == null ? "stop" : cached.finishReason());
        var usage = root.putObject("usage");
        usage.put("prompt_tokens", cached.usage().promptTokens());
        usage.put("completion_tokens", cached.usage().completionTokens());
        usage.put("total_tokens", cached.usage().totalTokens());
        return root;
    }
}
