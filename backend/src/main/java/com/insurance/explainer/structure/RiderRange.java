package com.insurance.explainer.structure;

/**
 * 구조 분석으로 찾아낸 특약의 범위.
 * riderId는 문서 안에서만 겹치지 않으면 되는 구조 기반 문자열이다(명세서 7장).
 */
public record RiderRange(String riderId, String name, int pageStart) {
}
