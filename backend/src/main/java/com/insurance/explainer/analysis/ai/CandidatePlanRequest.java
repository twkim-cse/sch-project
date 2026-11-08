package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// POST /ai/extract/candidates/plan 요청. mode는 "recall"(기본)/"broad"/"full".
// focusItems가 null이면 sourceRequest의 checkItems 전체를 대상으로 한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidatePlanRequest(ExtractRequest sourceRequest, String mode, List<Integer> focusItems) {
}
