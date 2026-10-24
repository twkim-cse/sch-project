package com.insurance.explainer.mockapi.documents.dto;

// GET /api/documents/{documentId}/clauses/{clauseId} 응답(명세서 4장, 원문 보기).
public record ClauseResponse(
        String clauseId,
        String section,
        String article,
        String articleTitle,
        String paragraph,
        int page,
        String text
) {
}
