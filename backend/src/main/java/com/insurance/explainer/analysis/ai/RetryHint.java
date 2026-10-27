package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// 숫자 검사(F-14)에 실패해 다시 요청할 때 담는 이유(명세서 5장). F-14 구현 전까지는 쓰이지 않는다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RetryHint(List<String> unmatchedNumbers) {
}
