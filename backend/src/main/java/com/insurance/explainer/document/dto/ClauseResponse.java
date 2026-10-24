package com.insurance.explainer.document.dto;

import com.insurance.explainer.structure.Clause;

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
    public static ClauseResponse from(Clause clause) {
        return new ClauseResponse(clause.clauseId(), clause.section(), clause.article(),
                clause.articleTitle(), clause.paragraph(), clause.page(), clause.text());
    }
}
