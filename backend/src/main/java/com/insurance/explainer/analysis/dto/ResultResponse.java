package com.insurance.explainer.analysis.dto;

import java.util.List;

// GET /api/analyses/{analysisId}/result 응답(명세서 4장).
public record ResultResponse(
        String analysisId,
        DocumentSummary document,
        boolean viewAll,
        List<SelectedRider> selectedRiders,
        List<CheckSummaryItem> checkSummary,
        List<ResultGroup> groups
) {
}
