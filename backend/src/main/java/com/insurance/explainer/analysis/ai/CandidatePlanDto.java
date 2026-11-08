package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// 무손실 분할 계획. fragments·source_digest 등 검증용 필드는 AI 서버가 merge
// 때 다시 계산해 대조하므로 백엔드에서는 조각 목록만 있으면 된다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidatePlanDto(String planId, List<CandidatePartDto> parts) {
}
