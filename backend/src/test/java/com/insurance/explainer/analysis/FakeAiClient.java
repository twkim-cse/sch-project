package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CandidateMergeRequest;
import com.insurance.explainer.analysis.ai.CandidateMergeResponse;
import com.insurance.explainer.analysis.ai.CandidatePartDto;
import com.insurance.explainer.analysis.ai.CandidatePlanDto;
import com.insurance.explainer.analysis.ai.CandidatePlanRequest;
import com.insurance.explainer.analysis.ai.CandidatePlanResponse;
import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.analysis.ai.SelectionDto;

import java.util.List;
import java.util.function.Function;

/**
 * 테스트에서 실제 네트워크 없이 AiClient를 대체하는 가짜 구현.
 * <p>
 * candidatesPlan/candidatesMerge는 따로 stub하지 않으면 "조각 하나 = 원본 요청
 * 전체"인 그대로 통과(identity) 계획/병합을 돌려준다. 그래서 extract()만 stub한
 * 기존 테스트는 조각 나누기와 무관하게 그대로 동작한다.
 */
class FakeAiClient extends AiClient {

    private Function<ExtractRequest, ExtractResponse> extractFn = req -> new ExtractResponse(List.of());
    private Function<ExplainRequest, ExplainResponse> explainFn = req -> new ExplainResponse(List.of());
    private Function<CandidatePlanRequest, CandidatePlanResponse> candidatesPlanFn = req -> new CandidatePlanResponse(
            new SelectionDto("fake-selection"),
            new CandidatePlanDto("fake-plan", List.of(new CandidatePartDto("fake-part-0", req.sourceRequest()))));
    private Function<CandidateMergeRequest, CandidateMergeResponse> candidatesMergeFn = req -> {
        List<ExtractionDto> merged = req.partResults().stream()
                .flatMap(part -> part.response().extractions().stream())
                .toList();
        return new CandidateMergeResponse(merged);
    };

    FakeAiClient() {
        super(null);
    }

    void onExtract(Function<ExtractRequest, ExtractResponse> fn) {
        this.extractFn = fn;
    }

    void onExplain(Function<ExplainRequest, ExplainResponse> fn) {
        this.explainFn = fn;
    }

    void onCandidatesPlan(Function<CandidatePlanRequest, CandidatePlanResponse> fn) {
        this.candidatesPlanFn = fn;
    }

    void onCandidatesMerge(Function<CandidateMergeRequest, CandidateMergeResponse> fn) {
        this.candidatesMergeFn = fn;
    }

    @Override
    public ExtractResponse extract(ExtractRequest request) {
        return extractFn.apply(request);
    }

    @Override
    public ExplainResponse explain(ExplainRequest request) {
        return explainFn.apply(request);
    }

    @Override
    public CandidatePlanResponse candidatesPlan(CandidatePlanRequest request) {
        return candidatesPlanFn.apply(request);
    }

    @Override
    public CandidateMergeResponse candidatesMerge(CandidateMergeRequest request) {
        return candidatesMergeFn.apply(request);
    }
}
