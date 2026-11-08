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

/**
 * 명세서 5장 "호출 규칙": 대기 시간은 넉넉히 두고, 오류나 시간 초과가 나면
 * 분석을 FAILED(ANALYSIS_FAILED)로 끝낸다.
 * <p>
 * 일시적 오류(429/503 등)의 재시도·백오프·cooldown은 AI 서버가 이미 내부에서
 * 책임진다(명세서 5장, AI 서버의 extract_resilience 재시도 정책 참고). 그래서
 * 여기서는 같은 HTTP 요청을 바로 한 번 더 반복하지 않는다 — 특히 413/422처럼
 * 영구적으로 실패하는 요청까지 즉시 재반복하면 AI 서버에 불필요한 부담만
 * 늘어난다.
 * <p>
 * 대기 시간은 고정 5분이 아니라 요청 크기(배치 수)에 비례해서 늘어난다. AI
 * 서버는 조항을 배치로 나눠 순서대로 Gemini를 부르므로(명세서 5장), 조항이
 * 많을수록 끝까지 걸리는 시간도 길어진다. 배치 하나당 1분을 더하고, 작은
 * 요청에서는 5분보다 줄지 않도록 바닥을 둔다(기존 동작 유지). 너무 커지는
 * 것을 막기 위한 안전장치로 위쪽 한도도 둔다.
 */
@Component
public class AiClient {

    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PER_BATCH_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(60);

    // AI 서버의 extract_planning.py MAX_BATCH_CLAUSES와 맞춘다. extract 요청의
    // 조항 수를 이 값으로 나누면 AI 서버가 실제로 처리할 배치 수에 가까워진다.
    private static final int CLAUSES_PER_BATCH = 25;

    // 조항이 많은 약관은 candidates/plan·merge 응답(조각마다 원본 조항을 그대로
    // 돌려줌)이 WebClient 기본 메모리 버퍼(256KB)를 쉽게 넘는다. AI 서버의 조각당
    // 입력 한도(약 16MB)보다 넉넉한 상한만 두고, 그 한도 자체를 늘려주진 않는다.
    private static final int MAX_RESPONSE_BYTES = 40 * 1024 * 1024;

    private final WebClient aiWebClient;

    public AiClient(WebClient aiWebClient) {
        this.aiWebClient = aiWebClient == null ? null : aiWebClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
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
        } catch (RuntimeException failure) {
            throw new AiCallFailedException(uri + " 호출이 실패했습니다.", failure);
        }
    }
}
