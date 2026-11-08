package com.insurance.explainer.analysis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리 분석 작업 저장소. 서버를 재시작하면 비워진다.
@Component
public class AnalysisStore {

    private final Map<String, AnalysisRecord> analyses = new ConcurrentHashMap<>();
    private final Map<String,CandidateCheckpoint> candidates=new ConcurrentHashMap<>();
    public void candidateCheckpoint(String id,CandidateCheckpoint checkpoint){candidates.put(id,checkpoint);}
    public CandidateCheckpoint candidateCheckpoint(String id){return candidates.get(id);}

    public void save(AnalysisRecord record) {
        analyses.put(record.analysisId(), record);
    }

    public AnalysisRecord get(String analysisId) {
        return analyses.get(analysisId);
    }

    public void delete(String analysisId) {
        analyses.remove(analysisId);
        candidates.remove(analysisId);
    }

    /** F-21: 문서가 삭제(즉시 또는 보관 기한 만료)될 때 그 문서로 만든 분석도 함께 지우기 위함. */
    public List<String> findIdsByDocumentId(String documentId) {
        return analyses.values().stream()
                .filter(a -> documentId.equals(a.documentId()))
                .map(AnalysisRecord::analysisId)
                .toList();
    }
}
