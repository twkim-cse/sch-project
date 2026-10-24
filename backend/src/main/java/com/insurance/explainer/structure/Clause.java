package com.insurance.explainer.structure;

/**
 * 조항 단위로 분할된 결과 하나.
 * text는 인용 대조 전 정리 규칙(명세서 6장: 줄바꿈→공백, 연속 공백 축약)이 이미 적용된 값이다.
 */
public record Clause(
        String clauseId,
        String riderId, // 주계약이면 null
        String section,
        String article,
        String articleTitle, // 없으면 null
        String paragraph,
        int page,
        boolean fromTable,
        String text
) {
}
