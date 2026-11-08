package com.insurance.explainer.analysis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리 분석 작업 저장소. 서버를 재시작하면 비워진다.
@Component
public class AnalysisStore {

    private final Map<String, AnalysisRecord> analyses = new ConcurrentHashMap<>();
    // 추출 조각 진행 상태 체크포인트(candidate-progress 조회용). 분석과 생명주기를 같이한다.
    private final Map<String, CandidateCheckpoint> candidateCheckpoints = new ConcurrentHashMap<>();

    public void save(AnalysisRecord record) {
        analyses.put(record.analysisId(), record);
    }

    public AnalysisRecord get(String analysisId) {
        return analyses.get(analysisId);
    }

    public void candidateCheckpoint(String analysisId, CandidateCheckpoint checkpoint) {
        candidateCheckpoints.put(analysisId, checkpoint);
    }

    public CandidateCheckpoint candidateCheckpoint(String analysisId) {
        return candidateCheckpoints.get(analysisId);
    }

    public void delete(String analysisId) {
        analyses.remove(analysisId);
        candidateCheckpoints.remove(analysisId);
    }

    /** F-21: 문서가 삭제(즉시 또는 보관 기한 만료)될 때 그 문서로 만든 분석도 함께 지우기 위함. */
    public List<String> findIdsByDocumentId(String documentId) {
        return analyses.values().stream()
                .filter(a -> documentId.equals(a.documentId()))
                .map(AnalysisRecord::analysisId)
                .toList();
    }
}
