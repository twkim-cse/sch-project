package com.insurance.explainer.mockapi;

// 모든 오류 응답의 형식 (명세서 4장 "오류 응답"). { "code": "...", "message": "..." }
public record ApiError(String code, String message) {
}
