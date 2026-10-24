package com.insurance.explainer.structure;

/**
 * 조항 구조(관·조·항)를 인식하지 못해 분석을 중단해야 할 때 던진다.
 * 오류 코드는 명세서 6장의 STRUCTURE_FAILED를 그대로 쓴다.
 */
public class StructureAnalysisException extends RuntimeException {

    public static final String CODE = "STRUCTURE_FAILED";

    public StructureAnalysisException(String message) {
        super(message);
    }
}
