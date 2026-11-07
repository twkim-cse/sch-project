package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** AI HTTP boundary: lossless extraction coordinator and bounded explain requests.
 * Gemini stage retries are owned by FastAPI; no whole-request HTTP retry here. */
@Component
@org.springframework.context.annotation.DependsOn("localStateLease")
public class AiClient {

    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PER_BATCH_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(60);

    private final WebClient aiWebClient;
    private final BulkExtractCoordinator bulk;
    private final DurableExplainCalls explanations;
    private final boolean durable;

    public AiClient(WebClient aiWebClient) {
        this(aiWebClient, aiWebClient == null ? null : new BulkExtractCoordinator(aiWebClient));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiClient(WebClient aiWebClient,@org.springframework.beans.factory.annotation.Value("${ai.checkpoint-dir:}") String directory) {
        this(aiWebClient,new BulkExtractCoordinator(aiWebClient,Duration.ofSeconds(12),
                com.insurance.explainer.persistence.LocalStateFiles.optional(directory,"extract")),
                new DurableExplainCalls(aiWebClient,com.insurance.explainer.persistence.LocalStateFiles.optional(directory,"explain")),
                directory!=null && !directory.isBlank());
    }

    AiClient(WebClient aiWebClient, BulkExtractCoordinator coordinator) {
        this(aiWebClient,coordinator,new DurableExplainCalls(aiWebClient,null),false);
    }
    AiClient(WebClient aiWebClient,BulkExtractCoordinator coordinator,DurableExplainCalls explanations,boolean durable) {
        this.aiWebClient = aiWebClient;
        this.bulk = coordinator;
        this.explanations=explanations;this.durable=durable;
    }

    public ExtractResponse extract(ExtractRequest request) {
        return bulk.extract(request);
    }

    public BulkExtractCoordinator.Progress extractProgress(String analysisId) {
        return bulk == null ? null : bulk.progress(analysisId);
    }

    public boolean canResumeExtract(String analysisId) {
        if(bulk==null)return false;
        var progress=bulk.progress(analysisId);
        return bulk.canResume(analysisId) || (progress!=null && progress.fullDocumentSuccess() && explanations.canResume(analysisId));
    }
    public boolean durableEnabled(){return durable;}

    public void forgetExtract(String analysisId) {
        if (bulk != null) bulk.forget(analysisId);
        explanations.forget(analysisId);
    }

    public ExplainResponse explain(ExplainRequest request) {
        // AI 서버는 explain 항목을 하나씩 순서대로 처리하므로(초안·검토를 각각
        // 호출), 항목 수 자체가 배치 수와 같다.
        Duration timeout = batchTimeout(request.items().size());
        try {
            return explanations.call(request,()->aiWebClient.post()
                .uri("/ai/explain")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ExplainResponse.class)
                .block(timeout));
        } catch (RuntimeException e) {
            // Gemini retries are owned by the AI stage budget, not this HTTP client.
            throw new AiCallFailedException("AI explain 요청에 실패했습니다.", e);
        }
    }

    static Duration batchTimeout(int batchCount) {
        Duration scaled = PER_BATCH_TIMEOUT.multipliedBy(Math.max(batchCount, 1));
        Duration withFloor = scaled.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : scaled;
        return withFloor.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : withFloor;
    }

}
