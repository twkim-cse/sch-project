package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// review: "pass" 또는 "fail". fail이면 reviewReason에 이유가 담긴다(명세서 5장).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CardDto(String itemId, String title, String explanation, String review, String reviewReason) {
}
