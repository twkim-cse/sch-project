package com.insurance.explainer.analysis;

import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.structure.Clause;

import java.util.List;

/**
 * 분석 작업 한 건. {@link AnalysisStore}에 저장해 두고, 단계가 진행될 때마다
 * 새 레코드로 덮어쓴다(F-08 작업 관리).
 */
public record AnalysisRecord(
        String analysisId,
        String documentId,
        AnalysisStatus status,
        AnalysisStep step, // RUNNING일 때만 의미 있음
        boolean viewAll,
        String productName,
        String versionLabel,
        Integer pageCount,
        List<RiderInfo> selectedRiders,
        List<Clause> clauses, // 분석 대상(주계약 + 선택 특약) 조항. AI 호출에 씀
        List<CardResult> cards, // FINALIZING 이후에만 채워짐
        String errorCode,
        String errorMessage
) {

    public static AnalysisRecord queued(String analysisId, String documentId, boolean viewAll,
                                         String productName, String versionLabel, Integer pageCount,
                                         List<RiderInfo> selectedRiders, List<Clause> clauses) {
        return new AnalysisRecord(analysisId, documentId, AnalysisStatus.QUEUED, null, viewAll,
                productName, versionLabel, pageCount, selectedRiders, clauses, List.of(), null, null);
    }

    public AnalysisRecord withStep(AnalysisStep newStep) {
        return new AnalysisRecord(analysisId, documentId, AnalysisStatus.RUNNING, newStep, viewAll,
                productName, versionLabel, pageCount, selectedRiders, clauses, cards, null, null);
    }

    public AnalysisRecord done(List<CardResult> finalCards) {
        return new AnalysisRecord(analysisId, documentId, AnalysisStatus.DONE, step, viewAll,
                productName, versionLabel, pageCount, selectedRiders, clauses, finalCards, null, null);
    }

    public AnalysisRecord failed(String errorCode, String errorMessage) {
        return new AnalysisRecord(analysisId, documentId, AnalysisStatus.FAILED, step, viewAll,
                productName, versionLabel, pageCount, selectedRiders, clauses, cards, errorCode, errorMessage);
    }
}
