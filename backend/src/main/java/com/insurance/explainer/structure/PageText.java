package com.insurance.explainer.structure;

/**
 * 문서 읽기(F-02/F-01)로 추출된 한 쪽의 글자.
 * 구조 분석(F-04)의 입력 단위다.
 */
public record PageText(int page, String text) {
}
