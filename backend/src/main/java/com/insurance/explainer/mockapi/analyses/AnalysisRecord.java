package com.insurance.explainer.mockapi.analyses;

import com.insurance.explainer.mockapi.documents.RiderInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜(mock) 분석 저장소 한 건. 상태 조회(GET) 호출 횟수를 세어
 * QUEUED/RUNNING(단계별) → DONE 전환을 흉내 낸다.
 */
public class AnalysisRecord {

    // step 목록(명세서 4장): 이 순서대로 1번 조회할 때마다 한 단계씩 진행한다.
    public static final String[] STEPS = {"EXTRACTING", "CHECKING_QUOTES", "EXPLAINING", "FINALIZING"};

    private final String analysisId;
    private final String documentId;
    private final List<String> riderIds;
    private final boolean viewAll;
    private final List<RiderInfo> selectedRiders;
    private final AtomicInteger statusCallCount = new AtomicInteger(0);

    public AnalysisRecord(String analysisId, String documentId, List<String> riderIds,
                           boolean viewAll, List<RiderInfo> selectedRiders) {
        this.analysisId = analysisId;
        this.documentId = documentId;
        this.riderIds = riderIds;
        this.viewAll = viewAll;
        this.selectedRiders = selectedRiders;
    }

    /** 상태를 조회할 때마다 호출해 현재 몇 번째 호출인지 센다(1부터 시작). */
    public int nextStatusCall() {
        return statusCallCount.incrementAndGet();
    }

    /** 현재까지 조회한 횟수(증가시키지 않고 읽기만). DONE 여부 판단용. */
    public int currentCallCount() {
        return statusCallCount.get();
    }

    public boolean isDone() {
        return currentCallCount() > STEPS.length;
    }

    public String analysisId() {
        return analysisId;
    }

    public String documentId() {
        return documentId;
    }

    public List<String> riderIds() {
        return riderIds;
    }

    public boolean viewAll() {
        return viewAll;
    }

    public List<RiderInfo> selectedRiders() {
        return selectedRiders;
    }
}
