package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.insurance.explainer.analysis.ai.CandidatePartDto;
import com.insurance.explainer.analysis.ai.CandidatePlanRequest;
import com.insurance.explainer.analysis.ai.CandidatePlanResponse;
import com.insurance.explainer.analysis.ai.ExtractResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 추출 조각(candidates/plan·merge) 처리 상태를 메모리에만 남겨 두는 임시
 * 체크포인트. GET /api/analyses/{id}/candidate-progress에서 진행 요약을
 * 보여주기 위한 용도이며, 원문·인용은 담지 않는다. 디스크 저장이나 Spring
 * 재시작 후 자동 재개는 아직 구현하지 않았다(summary()의 resumable/durable은
 * 항상 false).
 */
public record CandidateCheckpoint(CandidatePlanRequest source, CandidatePlanResponse plan,
                                   Map<String, ExtractResponse> succeeded, String failedPartId,
                                   String state, JsonNode coverage) {

    public CandidateCheckpoint {
        succeeded = Map.copyOf(succeeded);
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("state", state);
        summary.put("completedPartIds", new ArrayList<>(succeeded.keySet()));
        summary.put("pendingPartIds", plan.candidatePlan().parts().stream()
                .map(CandidatePartDto::partId)
                .filter(partId -> !succeeded.containsKey(partId))
                .toList());
        summary.put("failedPartId", failedPartId);
        summary.put("selectionId", plan.selection().selectionId());
        summary.put("planId", plan.candidatePlan().planId());
        summary.put("policyVersion", plan.selection().policyVersion());
        summary.put("coverage", coverage);
        summary.put("fullDocumentSuccess", false);
        summary.put("resumable", false);
        summary.put("durable", false);
        return summary;
    }
}
