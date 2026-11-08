package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// POST /ai/extract/candidates/plan 응답.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidatePlanResponse(SelectionDto selection, CandidatePlanDto candidatePlan) {
}
