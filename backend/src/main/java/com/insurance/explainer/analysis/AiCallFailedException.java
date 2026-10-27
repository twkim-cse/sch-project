package com.insurance.explainer.analysis;

// AI 서버 호출이 (1회 재시도 포함) 끝내 실패했을 때 던진다. 분석을 FAILED(ANALYSIS_FAILED)로 끝낸다.
public class AiCallFailedException extends RuntimeException {
    public AiCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
