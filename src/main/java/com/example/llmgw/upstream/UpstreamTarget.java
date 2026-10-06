package com.example.llmgw.upstream;

import java.time.Duration;

public record UpstreamTarget(
        String name,
        String vendor,
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration firstTokenTimeout,
        Duration readTimeout,
        double priceInPer1k,
        double priceOutPer1k) {

    public String completionsUri() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/chat/completions";
    }

    public double costOf(com.example.llmgw.protocol.Usage usage) {
        if (usage == null) {
            return 0;
        }
        return usage.promptTokens() / 1000.0 * priceInPer1k + usage.completionTokens() / 1000.0 * priceOutPer1k;
    }
}
