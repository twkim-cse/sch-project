package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExplainItemDto(
        String itemId,
        int checkItem,
        String section,
        String articleTitle,
        String quote,
        RetryHint retryHint
) {
}
