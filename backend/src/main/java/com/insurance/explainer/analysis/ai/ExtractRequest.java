package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// POST /ai/extract 요청(명세서 5장).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExtractRequest(String analysisId, List<Integer> checkItems, List<ClauseDto> clauses) {
}
