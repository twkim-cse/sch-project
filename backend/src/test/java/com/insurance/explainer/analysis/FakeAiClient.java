package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;

import java.util.function.Function;

/** 테스트에서 실제 네트워크 없이 AiClient를 대체하는 가짜 구현. */
class FakeAiClient extends AiClient {

    private Function<ExtractRequest, ExtractResponse> extractFn = req -> new ExtractResponse(java.util.List.of());
    private Function<ExplainRequest, ExplainResponse> explainFn = req -> new ExplainResponse(java.util.List.of());

    FakeAiClient() {
        super(null);
    }

    void onExtract(Function<ExtractRequest, ExtractResponse> fn) {
        this.extractFn = fn;
    }

    void onExplain(Function<ExplainRequest, ExplainResponse> fn) {
        this.explainFn = fn;
    }

    @Override
    public ExtractResponse extract(ExtractRequest request) {
        return extractFn.apply(request);
    }

    @Override
    public ExplainResponse explain(ExplainRequest request) {
        return explainFn.apply(request);
    }
}
