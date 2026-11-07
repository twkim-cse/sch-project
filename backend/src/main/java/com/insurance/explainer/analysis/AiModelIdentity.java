package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;

final class AiModelIdentity {
    static String read(WebClient client) {
        try {
            JsonNode response=client.get().uri("/health").retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(5));
            if(response!=null && response.path("ai_configured").asBoolean() && response.path("model").isTextual()
                    && !response.path("model").asText().isBlank() && response.path("model").asText().length()<128)
                return response.path("model").asText();
        }catch(Exception ignored){}
        throw new AiCallFailedException("AI 모델 설정 상태를 확인할 수 없습니다.",null);
    }
}
