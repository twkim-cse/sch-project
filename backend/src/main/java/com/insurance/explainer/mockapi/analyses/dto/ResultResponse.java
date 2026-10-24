package com.insurance.explainer.mockapi.analyses.dto;

import java.util.List;

// GET /api/analyses/{analysisId}/result 응답(명세서 4장). 예시 JSON을 그대로 고정해 돌려준다.
public record ResultResponse(
        String analysisId,
        DocumentSummary document,
        boolean viewAll,
        List<SelectedRider> selectedRiders,
        List<CheckSummaryItem> checkSummary,
        List<ResultGroup> groups
) {
}

record DocumentSummary(String productName, String versionLabel, int pageCount) {
}

record SelectedRider(String riderId, String name) {
}

record CheckSummaryItem(int checkItem, String name, String status, int cardCount) {
}

record ResultGroup(String scope, String riderId, String name, List<ResultCard> cards) {
}

record ResultCard(
        String cardId,
        int checkItem,
        String importance,
        String display,
        String title,
        String explanation,
        String quote,
        ClauseSource source,
        String clauseId,
        boolean fromTable
) {
}

record ClauseSource(String section, String article, String paragraph, int page) {
}
