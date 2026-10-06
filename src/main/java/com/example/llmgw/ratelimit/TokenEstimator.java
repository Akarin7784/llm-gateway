package com.example.llmgw.ratelimit;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.protocol.ChatCompletionRequest;
import org.springframework.stereotype.Component;

/**
 * Deliberately coarse. Running a real BPE encode on the admission path would put tens of
 * milliseconds of CPU in front of every request to answer a question that only needs to be
 * conservative: {@code prompt / 4 + cjk} tracks English well and treats CJK characters as roughly one
 * token each, which is the direction that over-counts rather than under-counts.
 *
 * The estimate is a reservation, not an invoice: the real usage settles it afterwards.
 */
@Component
public class TokenEstimator {

    private final int defaultCompletionTokens;

    public TokenEstimator(GatewayProperties properties) {
        this.defaultCompletionTokens = properties.getQuota().getDefaultCompletionTokens();
    }

    public long estimatePrompt(ChatCompletionRequest request) {
        long latin = 0;
        long cjk = 0;
        for (var message : request.messages()) {
            String content = message.content();
            if (content == null) {
                continue;
            }
            for (int i = 0; i < content.length(); i++) {
                if (isWide(content.charAt(i))) {
                    cjk++;
                } else {
                    latin++;
                }
            }
        }
        return Math.max(1, (latin + 3) / 4 + cjk);
    }

    public long estimateCompletion(ChatCompletionRequest request) {
        return request.maxTokens() == null ? defaultCompletionTokens : request.maxTokens();
    }

    public long estimateTotal(ChatCompletionRequest request) {
        return estimatePrompt(request) + estimateCompletion(request);
    }

    private static boolean isWide(char value) {
        return value >= 0x3040 && value <= 0x9FFF
                || value >= 0xAC00 && value <= 0xD7AF
                || value >= 0xF900 && value <= 0xFAFF;
    }
}
