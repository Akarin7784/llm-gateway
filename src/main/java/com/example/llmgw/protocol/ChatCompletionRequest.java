package com.example.llmgw.protocol;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Canonical view of an inbound chat completion request. The raw JSON is always forwarded to
 * OpenAI-compatible upstreams so that unknown client fields survive the hop; this record only
 * carries what the gateway itself needs to make decisions.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        boolean stream,
        Integer maxTokens,
        Double temperature) {

    public boolean deterministic() {
        return temperature == null || temperature == 0.0;
    }

    public long promptCharacterCount() {
        if (messages == null) {
            return 0;
        }
        return messages.stream().mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum();
    }
}
