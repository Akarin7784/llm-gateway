package com.example.llmgw.cache;

import com.example.llmgw.auth.Tenant;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the digest a response is stored under.
 *
 * <p>Two decisions carry the weight. First, the field filter is a <em>deny</em> list: transport and
 * gateway-control fields are dropped and everything else participates. An allow list would silently
 * exclude any parameter the gateway does not know about yet, and unknown parameters routinely do
 * change the answer -- a cache miss is cheap, a wrong-cache hit is not.
 *
 * <p>Second, object keys are sorted recursively before hashing while array order is preserved,
 * because key order is incidental in JSON but message order is not.
 */
@Component
public class CacheKeyFactory {

    /** Absent from the digest: they select transport or harness behaviour, not answer content. */
    private static final List<String> EXCLUDED_FIELDS = List.of("stream", "stream_options", "user");

    private static final List<String> TRUNCATION_FINISH_REASONS = List.of("length", "content_filter");

    private final GatewayProperties properties;

    public CacheKeyFactory(GatewayProperties properties) {
        this.properties = properties;
    }

    public Optional<String> keyFor(Tenant tenant, JsonNode raw, ChatCompletionRequest request) {
        GatewayProperties.Cache cache = properties.getCache();
        if (!cache.isEnabled() || !request.deterministic()) {
            return Optional.empty();
        }
        ObjectNode semantic = raw.deepCopy();
        StringBuilder canonical = new StringBuilder();
        writeCanonical(semantic, canonical);
        String scope = cache.isTenantIsolation() ? tenant.id() + '|' : "";
        return Optional.of(scope + sha256(canonical.toString()));
    }

    /**
     * A truncated or policy-filtered completion is not a usable cache entry: replaying it would bake
     * an interrupted answer into the cache for the whole TTL.
     */
    public static boolean cacheableFinishReason(String finishReason) {
        return finishReason == null || !TRUNCATION_FINISH_REASONS.contains(finishReason);
    }

    private static void writeCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                writeCanonical(it.next(), out);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            out.append(']');
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                if (!EXCLUDED_FIELDS.contains(name) && !name.startsWith("_")) {
                    sorted.put(name, entry.getValue());
                }
            });
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(entry.getKey()).append("\":");
                writeCanonical(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        // Numbers are emitted by their textual form so 1 and 1.0 do not split the cache.
        out.append(node.isNumber() ? node.asText() : node.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
