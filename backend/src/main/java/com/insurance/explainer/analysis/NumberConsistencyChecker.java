package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CardDto;

/**
 * F-14 숫자 일치 검사: 해설의 기간·비율·횟수가 인용문의 숫자와 같은지 확인한다.
 * FINALIZING 단계에서 쓰는 자리다. 지금은 F-14를 아직 구현하지 않아
 * {@link PassthroughNumberConsistencyChecker}가 항상 통과시킨다.
 */
public interface NumberConsistencyChecker {
    boolean isConsistent(CardDto card, String quote);
}
