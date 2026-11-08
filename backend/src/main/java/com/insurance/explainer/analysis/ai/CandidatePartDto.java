package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// candidate_plan.parts[]의 한 조각. request는 그대로 기존 POST /ai/extract로
// 보낸다(명세서 5장 계약과 동일한 모양). input_bytes 등 나머지 필드는 지금은
// 쓰지 않아 무시한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidatePartDto(String partId, ExtractRequest request) {
}
