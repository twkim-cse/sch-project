package com.insurance.explainer.analysis;

/**
 * F-14 숫자 일치 검사: 해설(title·explanation)의 기간·비율·횟수가 인용문(quote)의
 * 숫자와 같은지 확인한다. FINALIZING 단계에서 쓴다.
 */
public interface NumberConsistencyChecker {
    NumberCheckResult check(String title, String explanation, String quote);
}
