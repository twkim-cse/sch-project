package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// POST /ai/extract/candidates/merge 응답. coverage/selection_id/plan_id는
// 지금은 쓰지 않아 무시한다(나중에 "일부만 검토함" 안내에 쓸 수 있다).
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidateMergeResponse(List<ExtractionDto> extractions) {
}
