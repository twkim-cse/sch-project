package com.insurance.explainer.health;

// 프론트엔드 ⇄ 백엔드 응답은 camelCase를 쓴다 (기능명세서 4장).
public record HealthResponse(String backend, String ai) {
}
