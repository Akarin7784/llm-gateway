package com.example.llmgw.protocol;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
