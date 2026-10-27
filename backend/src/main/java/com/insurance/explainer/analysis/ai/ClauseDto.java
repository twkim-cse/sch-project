package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// 백엔드 ⇄ AI는 snake_case를 쓴다(명세서 5장).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClauseDto(
        String clauseId,
        String riderId, // 주계약이면 null
        String section,
        String article,
        String articleTitle,
        String paragraph,
        int page,
        boolean fromTable,
        String text
) {
}
