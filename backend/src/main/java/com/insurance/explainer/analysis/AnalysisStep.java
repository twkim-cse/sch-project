package com.insurance.explainer.analysis;

// 명세서 4장 GET /api/analyses/{analysisId} 의 step 값. 순서대로 진행한다.
public enum AnalysisStep {
    EXTRACTING,       // 조항 찾는 중 (F-09, /ai/extract)
    CHECKING_QUOTES,  // 원문과 대조 중 (F-10, 지금은 통과만 시킴)
    EXPLAINING,       // 쉬운 말로 푸는 중 (F-11/F-12/F-13, /ai/explain)
    FINALIZING        // 검사·정리 중 (F-14 숫자검사 지금은 통과, 중요도·NOT_FOUND 채움)
}
