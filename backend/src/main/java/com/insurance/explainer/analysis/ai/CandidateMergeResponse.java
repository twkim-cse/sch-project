package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// POST /ai/extract/candidates/merge 응답. coverage/selectionId/planId는
// 체크포인트 조회(candidate-progress)용으로 보관한다. coverage는 어떤 범위를
// 실제로 분석했는지를 나타낼 뿐 원문·인용은 담지 않는다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidateMergeResponse(List<ExtractionDto> extractions, JsonNode coverage,
                                      String selectionId, String planId) {

    public CandidateMergeResponse(List<ExtractionDto> extractions) {
        this(extractions, null, null, null);
    }
}
