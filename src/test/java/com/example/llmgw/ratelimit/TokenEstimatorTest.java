package com.example.llmgw.ratelimit;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.example.llmgw.protocol.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final TokenEstimator estimator = new TokenEstimator(properties);

    private ChatCompletionRequest request(String content) {
        return new ChatCompletionRequest("gw-demo", List.of(new ChatMessage("user", content)),
                false, null, null);
    }

    @Test
    void latinTextIsRoughlyOneTokenPerFourCharacters() {
        assertThat(estimator.estimatePrompt(request("a".repeat(40)))).isEqualTo(10L);
    }

    @Test
    void cjkCountsPerCharacterBecauseItTokenisesAlmostOneForOne() {
        assertThat(estimator.estimatePrompt(request("你好世界".repeat(5)))).isEqualTo(20L);
    }

    @Test
    void emptyPromptStillReservesOneToken() {
        assertThat(estimator.estimatePrompt(request(""))).isEqualTo(1L);
    }

    /** Rounding must go up: the stated policy is to over-reserve rather than under-reserve. */
    @Test
    void partialWordsRoundUpInsteadOfVanishing() {
        assertThat(estimator.estimatePrompt(request("abc"))).isGreaterThanOrEqualTo(1L);
        assertThat(estimator.estimatePrompt(request("hi 你好"))).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void completionReservesMaxTokensWhenTheClientGaveACap() {
        ChatCompletionRequest capped = new ChatCompletionRequest("gw-demo",
                List.of(new ChatMessage("user", "hi")), false, 1234, null);
        assertThat(estimator.estimateCompletion(capped)).isEqualTo(1234L);
    }

    /** The estimate is snapshotted at construction, the way configuration is at startup. */
    @Test
    void uncappedRequestsFallBackToTheConfiguredDefault() {
        properties.getQuota().setDefaultCompletionTokens(777);
        assertThat(new TokenEstimator(properties).estimateCompletion(request("hi"))).isEqualTo(777L);
        assertThat(estimator.estimateCompletion(request("hi"))).isEqualTo(512L);
    }
}
