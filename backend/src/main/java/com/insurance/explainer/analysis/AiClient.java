package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CandidateMergeRequest;
import com.insurance.explainer.analysis.ai.CandidateMergeResponse;
import com.insurance.explainer.analysis.ai.CandidatePlanRequest;
import com.insurance.explainer.analysis.ai.CandidatePlanResponse;
import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.function.Supplier;

/** AI HTTP boundary: bounded response aggregation and no automatic whole-request replay.
 * Upstream transient retries are owned by FastAPI; backend resume requires review.
 */
@Component
public class AiClient {

    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PER_BATCH_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(60);

    // AI 서버의 extract_planning.py MAX_BATCH_CLAUSES와 맞춘다. extract 요청의
    // 조항 수를 이 값으로 나누면 AI 서버가 실제로 처리할 배치 수에 가까워진다.
    private static final int CLAUSES_PER_BATCH = 25;

    private final WebClient aiWebClient;

    public AiClient(WebClient aiWebClient) {
        this.aiWebClient = aiWebClient == null ? null : aiWebClient.mutate().codecs(c -> c.defaultCodecs()
                .maxInMemorySize(40 * 1024 * 1024)).build();
    }

    public ExtractResponse extract(ExtractRequest request) {
        Duration timeout = batchTimeout(ceilDiv(request.clauses().size(), CLAUSES_PER_BATCH));
        return callOnce("/ai/extract", () -> aiWebClient.post()
                .uri("/ai/extract")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ExtractResponse.class)
                .block(timeout));
    }

    /**
     * 조항이 많은 약관을 /ai/extract에 통째로 보내면 413(배치/예산 초과)이
     * 나므로, AI 서버의 무손실 분할 계획을 먼저 받는다(명세서 5장, 후보 필터링
     * 연동). 이 호출은 Gemini를 부르지 않고 계산만 하므로 최소 대기 시간만
     * 쓴다.
     */
    public CandidatePlanResponse candidatesPlan(CandidatePlanRequest request) {
        return callOnce("/ai/extract/candidates/plan", () -> aiWebClient.post()
                .uri("/ai/extract/candidates/plan")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CandidatePlanResponse.class)
                .block(MIN_TIMEOUT));
    }

    /**
     * candidatesPlan이 나눠준 조각들을 각각 /ai/extract로 호출한 뒤, 그 결과를
     * 모아 원래 조항 기준으로 합친다(위치·원문 대조는 AI 서버가 검증한다).
     * 이 호출도 Gemini를 부르지 않는다.
     */
    public CandidateMergeResponse candidatesMerge(CandidateMergeRequest request) {
        return callOnce("/ai/extract/candidates/merge", () -> aiWebClient.post()
                .uri("/ai/extract/candidates/merge")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CandidateMergeResponse.class)
                .block(MIN_TIMEOUT));
    }

    public ExplainResponse explain(ExplainRequest request) {
        // AI 서버는 explain 항목을 하나씩 순서대로 처리하므로(초안·검토를 각각
        // 호출), 항목 수 자체가 배치 수와 같다.
        Duration timeout = batchTimeout(request.items().size());
        return callOnce("/ai/explain", () -> aiWebClient.post()
                .uri("/ai/explain")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ExplainResponse.class)
                .block(timeout));
    }

    static Duration batchTimeout(int batchCount) {
        Duration scaled = PER_BATCH_TIMEOUT.multipliedBy(Math.max(batchCount, 1));
        Duration withFloor = scaled.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : scaled;
        return withFloor.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : withFloor;
    }

    private static int ceilDiv(int total, int perBatch) {
        return (total + perBatch - 1) / perBatch;
    }

    private <T> T callOnce(String uri, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException firstFailure) {
            // FastAPI owns bounded upstream retries/cooldown. Never immediately
            // replay a complete HTTP request, including permanent 413/422.
            throw new AiCallFailedException("AI 요청에 실패했습니다.", firstFailure);
        }
    }
}
