package com.insurance.explainer.analysis.dto;

public record ResultCard(
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
