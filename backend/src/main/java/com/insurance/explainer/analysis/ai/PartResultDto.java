package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// candidates/merge 요청에 담는 조각 하나의 결과. response는 그 조각을
// POST /ai/extract로 보내서 받은 응답 그대로({"extractions":[...]})다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PartResultDto(String partId, ExtractResponse response) {
}
