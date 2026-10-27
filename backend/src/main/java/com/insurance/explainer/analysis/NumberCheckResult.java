package com.insurance.explainer.analysis;

import java.util.List;

/**
 * F-14 숫자 일치 검사 결과. 어긋난 숫자가 있으면 {@code unmatchedNumbers}에 담겨
 * /ai/explain 재호출의 retry_hint로 그대로 쓰인다(명세서 5장).
 */
public record NumberCheckResult(boolean consistent, List<String> unmatchedNumbers) {

    public static NumberCheckResult ok() {
        return new NumberCheckResult(true, List.of());
    }
}
