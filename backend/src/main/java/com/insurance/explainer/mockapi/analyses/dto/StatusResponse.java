package com.insurance.explainer.mockapi.analyses.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.explainer.mockapi.ApiError;

// GET /api/analyses/{analysisId} 응답(명세서 4장). step 관련 필드는 RUNNING일 때만 채운다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusResponse(
        String analysisId,
        String status,
        String step,
        Integer stepIndex,
        Integer stepCount,
        ApiError error
) {
    public static StatusResponse running(String analysisId, String step, int stepIndex, int stepCount) {
        return new StatusResponse(analysisId, "RUNNING", step, stepIndex, stepCount, null);
    }

    public static StatusResponse done(String analysisId) {
        return new StatusResponse(analysisId, "DONE", null, null, null, null);
    }
}
