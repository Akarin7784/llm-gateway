package com.example.llmgw.router;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.upstream.UpstreamTarget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    private GatewayProperties properties;
    private ModelRouter router;
    private UpstreamTarget fast;
    private UpstreamTarget cheap;

    @BeforeEach
    void configure() {
        properties = new GatewayProperties();
        properties.getRouting().setMinVolume(4);
        properties.getRouting().setErrorThreshold(0.5);
        properties.getRouting().setOpenDuration(Duration.ofSeconds(30));

        // Fast but expensive, and slow but cheap: neither vendor wins on every dimension.
        properties.setUpstreams(List.of(
                upstream("fast-expensive", 0.006, 0.024),
                upstream("slow-cheap", 0.0006, 0.0024)));
        router = new ModelRouter(properties, new GatewayMetrics(new SimpleMeterRegistry()));

        List<UpstreamTarget> candidates = router.candidates("gw-demo");
        fast = candidateNamed(candidates, "fast-expensive");
        cheap = candidateNamed(candidates, "slow-cheap");
    }

    private static GatewayProperties.UpstreamConfig upstream(String name, double priceIn, double priceOut) {
        GatewayProperties.UpstreamConfig config = new GatewayProperties.UpstreamConfig();
        config.setName(name);
        config.setBaseUrl("http://127.0.0.1:9999/v1");
        config.setApiKey("sk");
        config.setModels(List.of("gw-demo"));
        config.setPriceInPer1k(priceIn);
        config.setPriceOutPer1k(priceOut);
        return config;
    }

    private static UpstreamTarget candidateNamed(List<UpstreamTarget> candidates, String name) {
        return candidates.stream().filter(target -> target.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void unmeasuredVendorsAreTriedBeforeAnythingRankedByEvidence() {
        router.recordSuccess(cheap, 400);
        assertThat(router.candidates("gw-demo").get(0))
                .as("a vendor with no samples must earn its own measurement")
                .isEqualTo(fast);
    }

    @Test
    void measuredVendorWinsOnObservedLatency() {
        for (int i = 0; i < 5; i++) {
            router.recordSuccess(fast, 40);
            router.recordSuccess(cheap, 400);
        }
        assertThat(router.candidates("gw-demo").get(0))
                .as("40ms beats 400ms even against a tenth of the price")
                .isEqualTo(fast);
    }

    @Test
    void latencyWeightsCanBeInvertedToBuyCheaperAnswers() {
        for (int i = 0; i < 5; i++) {
            router.recordSuccess(fast, 40);
            router.recordSuccess(cheap, 400);
        }
        properties.getRouting().setLatencyWeight(0.01);
        properties.getRouting().setCostWeight(4.0);

        assertThat(router.candidates("gw-demo").get(0)).isEqualTo(cheap);
    }

    /**
     * The shrinkage rule in isolation: one timeout out of one attempt keeps the vendor in play, the
     * same rate at the volume floor takes it out. Without this a vendor is starved of exactly the
     * samples its breaker needs in order to decide anything.
     */
    @Test
    void lowConfidenceErrorsDoNotSentenceAVendorButFullVolumeOnesDo() {
        for (int i = 0; i < 5; i++) {
            router.recordSuccess(cheap, 400);
        }
        router.recordFailure(fast);
        assertThat(router.candidates("gw-demo").get(0)).as("1 of 1").isEqualTo(fast);

        for (int i = 0; i < 3; i++) {
            router.recordFailure(fast);
        }
        assertThat(router.candidates("gw-demo").get(0)).as("4 of 4 at the floor").isEqualTo(cheap);
    }

    @Test
    void anOpenVendorLeavesTheCandidateListEntirely() {
        for (int i = 0; i < 5; i++) {
            router.recordSuccess(cheap, 40);
        }
        for (int i = 0; i < 8; i++) {
            router.recordFailure(fast);
        }

        assertThat(router.candidates("gw-demo")).containsExactly(cheap);
    }

    /** Prefer a forced route over a guaranteed 503: a partial outage must not become a total one. */
    @Test
    void fallsBackToForcedRoutingWhenEverythingIsOpen() {
        for (int i = 0; i < 8; i++) {
            router.recordFailure(fast);
            router.recordFailure(cheap);
        }

        assertThat(router.candidates("gw-demo"))
                .as("both vendors are open but traffic still has somewhere to go")
                .containsExactly(fast, cheap);
    }

    @Test
    void unknownModelIsRejectedBeforeAnyUpstreamIsCharged() {
        assertThatThrownBy(() -> router.candidates("gpt-nope")).isInstanceOf(GatewayException.class);
    }
}
