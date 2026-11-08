package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// POST /ai/extract/candidates/merge 요청. plan 요청과 같은 source_request/
// mode/focus_items에 selection_id·plan_id·조각별 결과를 더해 보낸다. AI
// 서버가 같은 입력으로 계획을 다시 만들어 selection_id/plan_id를 대조하므로
// candidates/plan 요청과 정확히 같은 source_request/mode/focus_items을 써야 한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidateMergeRequest(
        ExtractRequest sourceRequest,
        String mode,
        List<Integer> focusItems,
        String selectionId,
        String planId,
        List<PartResultDto> partResults
) {
}
