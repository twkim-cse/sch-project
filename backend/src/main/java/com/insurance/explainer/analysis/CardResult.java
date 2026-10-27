package com.insurance.explainer.analysis;

/**
 * FINALIZING을 마친 카드 하나. GET .../result 응답(명세서 4장)으로 그대로 옮겨진다.
 * display가 QUOTE_ONLY면 title·explanation은 null이다(F-12/F-14 검증 실패).
 */
public record CardResult(
        String cardId,
        int checkItem,
        String riderId, // 주계약이면 null → MAIN 그룹, 아니면 RIDER 그룹
        String importance,
        String display, // FULL 또는 QUOTE_ONLY
        String title,
        String explanation,
        String quote,
        String section,
        String article,
        String paragraph,
        int page,
        String clauseId,
        boolean fromTable
) {
}
