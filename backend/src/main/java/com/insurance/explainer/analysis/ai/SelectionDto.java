package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// 후보 선정 메타데이터. selectionId는 candidates/merge 요청에 그대로 다시
// 실어 보내야 한다. coverage는 어떤 범위를 실제로 분석했는지(원문·인용은 담지
// 않는다)를 나타내므로 체크포인트 조회용으로 그대로 보관한다. 나머지 필드는
// 지금은 쓰지 않아 무시한다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectionDto(String selectionId, String policyVersion, JsonNode coverage) {

    public SelectionDto(String selectionId) {
        this(selectionId, null, null);
    }
}
