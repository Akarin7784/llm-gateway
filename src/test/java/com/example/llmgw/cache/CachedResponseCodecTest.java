package com.example.llmgw.cache;

import com.example.llmgw.protocol.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachedResponseCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CachedResponseCodec codec = new CachedResponseCodec(mapper);

    @Test
    void extractsContentFinishReasonAndUsage() throws Exception {
        JsonNode vendor = mapper.readTree("""
                {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,
                 "model":"gw-demo","choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"the answer"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16}}""");

        CachedResponse cached = codec.fromVendor(vendor, "gw-demo", 120).orElseThrow();
        assertThat(cached.content()).isEqualTo("the answer");
        assertThat(cached.finishReason()).isEqualTo("stop");
        assertThat(cached.usage().totalTokens()).isEqualTo(16);
    }

    /** A length-truncated answer must not be frozen into the cache for the whole TTL. */
    @Test
    void refusesToCacheTruncatedOrFilteredCompletions() throws Exception {
        JsonNode truncated = mapper.readTree("""
                {"choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant","content":"cut"}}]}""");
        assertThat(codec.fromVendor(truncated, "gw-demo", 10)).isEmpty();

        JsonNode filtered = mapper.readTree("""
                {"choices":[{"index":0,"finish_reason":"content_filter","message":{"role":"assistant","content":""}}]}""");
        assertThat(codec.fromVendor(filtered, "gw-demo", 10)).isEmpty();
    }

    @Test
    void replayedAnswerKeepsTheOpenAiShapeAndReportsTheOriginalUsage() {
        CachedResponse cached = new CachedResponse("gw-demo", "the answer", "stop", Usage.of(11, 5),
                1_700_000_000_000L, 120);
        JsonNode json = codec.toCompletionJson(cached);

        assertThat(json.path("object").asText()).isEqualTo("chat.completion");
        assertThat(json.path("choices").get(0).path("message").path("content").asText()).isEqualTo("the answer");
        assertThat(json.path("choices").get(0).path("message").path("role").asText()).isEqualTo("assistant");
        assertThat(json.path("usage").path("total_tokens").asInt()).isEqualTo(16);
    }

    @Test
    void streamAssemblyIsOnlyCacheableWhenItEndedCleanly() {
        assertThat(codec.fromStream("partial", "length", Usage.of(1, 1), "gw-demo", 5)).isEmpty();
        assertThat(codec.fromStream("complete", "stop", Usage.of(1, 1), "gw-demo", 5)).isPresent();
        assertThat(codec.fromStream(null, "stop", Usage.of(1, 1), "gw-demo", 5)).isEmpty();
    }
}
