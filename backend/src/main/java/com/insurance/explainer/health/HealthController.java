package com.insurance.explainer.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@RestController
public class HealthController {

    private final WebClient aiWebClient;
    private final long healthTimeoutMs;

    public HealthController(WebClient aiWebClient,
                             @Value("${ai.health-timeout-ms:2000}") long healthTimeoutMs) {
        this.aiWebClient = aiWebClient;
        this.healthTimeoutMs = healthTimeoutMs;
    }

    // backend 자신의 상태와, AI 서버(/ai/health)의 상태를 함께 반환한다.
    // AI 호출이 시간 제한을 넘기거나 실패하면 ai: "DOWN"으로 응답한다.
    @GetMapping("/api/health")
    public HealthResponse health() {
        String aiStatus = checkAiHealth();
        return new HealthResponse("UP", aiStatus);
    }

    private String checkAiHealth() {
        try {
            Map<?, ?> body = aiWebClient.get()
                    .uri("/ai/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(healthTimeoutMs));
            if (body != null && "UP".equals(body.get("status"))) {
                return "UP";
            }
            return "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
