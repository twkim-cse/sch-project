package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// 후보 선정 메타데이터. coverage 등 나머지 필드는 지금은 쓰지 않아 무시한다.
// selectionId는 candidates/merge 요청에 그대로 다시 실어 보내야 한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectionDto(String selectionId) {
}
