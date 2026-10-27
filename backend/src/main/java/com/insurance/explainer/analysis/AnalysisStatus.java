package com.insurance.explainer.analysis;

// 명세서 4장 GET /api/analyses/{analysisId} 의 status 값.
public enum AnalysisStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
