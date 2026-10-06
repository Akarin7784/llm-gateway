package com.example.llmgw.cache;

import com.example.llmgw.auth.Tenant;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyFactoryTest {

    /** Mirrors the gateway's own leniency: unknown fields must not break canonicalisation. */
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final GatewayProperties properties = new GatewayProperties();
    private final CacheKeyFactory factory = new CacheKeyFactory(properties);
    private final Tenant tenant = new Tenant("t1", "T", 60, 60_000, 8);

    private Optional<String> key(String json) throws Exception {
        JsonNode raw = mapper.readTree(json);
        ChatCompletionRequest request = mapper.treeToValue(raw, ChatCompletionRequest.class);
        return factory.keyFor(tenant, raw, request);
    }

    @Test
    void sameSemanticRequestSameKeyRegardlessOfFieldOrder() throws Exception {
        Optional<String> a = key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}],"max_tokens":10}""");
        Optional<String> b = key("""
                {"max_tokens":10,"messages":[{"role":"user","content":"hi"}],"model":"gw-demo"}""");
        assertThat(a).isPresent();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void transportFieldsDoNotFragmentTheCache() throws Exception {
        Optional<String> blocking = key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}]}""");
        Optional<String> streaming = key("""
                {"model":"gw-demo","stream":true,"stream_options":{"include_usage":true},
                 "messages":[{"role":"user","content":"hi"}]}""");
        assertThat(streaming).isEqualTo(blocking);
    }

    @Test
    void gatewayControlFieldsAreNotPartOfTheIdentity() throws Exception {
        Optional<String> plain = key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}]}""");
        Optional<String> instrumented = key("""
                {"model":"gw-demo","_mock":{"chunks":3},"messages":[{"role":"user","content":"hi"}]}""");
        assertThat(instrumented).isEqualTo(plain);
    }

    @Test
    void changingTheAnswerChangesTheKey() throws Exception {
        Optional<String> base = key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}]}""");
        assertThat(key("""
                {"model":"gw-other","messages":[{"role":"user","content":"hi"}]}""")).isNotEqualTo(base);
        assertThat(key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hello"}]}""")).isNotEqualTo(base);
        assertThat(key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}],"max_tokens":512}"""))
                .isNotEqualTo(base);
    }

    @Test
    void messageOrderIsSignificant() throws Exception {
        Optional<String> ab = key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""");
        Optional<String> ba = key("""
                {"model":"gw-demo","messages":[{"role":"assistant","content":"b"},{"role":"user","content":"a"}]}""");
        assertThat(ab).isNotEqualTo(ba);
    }

    /** Sampling makes each call a different question, so caching it would return a stale roll. */
    @Test
    void nonZeroTemperatureIsNeverCacheable() throws Exception {
        assertThat(key("""
                {"model":"gw-demo","temperature":0.7,"messages":[{"role":"user","content":"hi"}]}""")).isEmpty();
        assertThat(key("""
                {"model":"gw-demo","temperature":0,"messages":[{"role":"user","content":"hi"}]}""")).isPresent();
    }

    @Test
    void disabledCacheProducesNoKey() throws Exception {
        properties.getCache().setEnabled(false);
        assertThat(key("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}]}""")).isEmpty();
    }

    /**
     * Isolation is the default. Turning it off trades one team's private context being reachable by
     * another for a higher hit ratio, so the two tenants must not collide unless that is configured.
     */
    @Test
    void tenantScopingIsOnByDefault() throws Exception {
        Tenant other = new Tenant("t2", "Other", 60, 60_000, 8);
        JsonNode raw = mapper.readTree("""
                {"model":"gw-demo","messages":[{"role":"user","content":"hi"}]}""");
        ChatCompletionRequest request = mapper.treeToValue(raw, ChatCompletionRequest.class);

        assertThat(factory.keyFor(tenant, raw, request)).isNotEqualTo(factory.keyFor(other, raw, request));

        properties.getCache().setTenantIsolation(false);
        assertThat(factory.keyFor(tenant, raw, request)).isEqualTo(factory.keyFor(other, raw, request));
    }
}
