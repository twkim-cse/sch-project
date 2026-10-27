package com.insurance.explainer.analysis;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리 분석 작업 저장소. 서버를 재시작하면 비워진다.
@Component
public class AnalysisStore {

    private final Map<String, AnalysisRecord> analyses = new ConcurrentHashMap<>();

    public void save(AnalysisRecord record) {
        analyses.put(record.analysisId(), record);
    }

    public AnalysisRecord get(String analysisId) {
        return analyses.get(analysisId);
    }
}
