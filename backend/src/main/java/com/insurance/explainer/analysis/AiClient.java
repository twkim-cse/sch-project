package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 명세서 5장 "호출 규칙": 대기 시간은 넉넉히(5분), 오류나 시간 초과가 나면 한 번
 * 다시 시도하고, 다시 실패하면 분석을 FAILED(ANALYSIS_FAILED)로 끝낸다.
 */
@Component
public class AiClient {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final WebClient aiWebClient;

    public AiClient(WebClient aiWebClient) {
        this.aiWebClient = aiWebClient;
    }

    public ExtractResponse extract(ExtractRequest request) {
        return callWithRetry("/ai/extract", () -> aiWebClient.post()
                .uri("/ai/extract")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ExtractResponse.class)
                .block(TIMEOUT));
    }

    public ExplainResponse explain(ExplainRequest request) {
        return callWithRetry("/ai/explain", () -> aiWebClient.post()
                .uri("/ai/explain")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ExplainResponse.class)
                .block(TIMEOUT));
    }

    private <T> T callWithRetry(String uri, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException firstFailure) {
            try {
                return call.get();
            } catch (RuntimeException secondFailure) {
                throw new AiCallFailedException(uri + " 호출이 재시도 후에도 실패했습니다.", secondFailure);
            }
        }
    }
}
