package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.dto.StartRequest;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStatus;
import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.Clause;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F-08 분석 작업 관리.
 * <p>
 * {@link #start}는 문서가 READY인지 확인하고, 주계약 + 선택한 특약(또는 viewAll이면
 * 전체)의 조항만 골라 QUEUED 상태로 저장한 뒤 바로 202로 끝난다. 실제 단계
 * (EXTRACTING → CHECKING_QUOTES → EXPLAINING → FINALIZING)는 {@link AnalysisRunner}가
 * 비동기로 진행한다. 실행을 별도 빈으로 분리한 이유는 {@link AnalysisRunner}의
 * 클래스 설명을 참고(self-invocation 문제로 같은 빈 안에서는 {@code @Async}가
 * 걸리지 않는다).
 */
@Service
public class AnalysisService {

    private final DocumentStore documentStore;
    private final AnalysisStore analysisStore;
    private final AnalysisRunner analysisRunner;
    @org.springframework.beans.factory.annotation.Autowired
    private AiClient aiClient;

    public AnalysisService(DocumentStore documentStore, AnalysisStore analysisStore, AnalysisRunner analysisRunner) {
        this.documentStore = documentStore;
        this.analysisStore = analysisStore;
        this.analysisRunner = analysisRunner;
    }

    public AnalysisRecord start(StartRequest request) {
        DocumentRecord document = documentStore.get(request.documentId());
        if (document == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        if (document.status() != DocumentStatus.READY) {
            throw ApiException.notReady("문서 확인 전 분석 요청");
        }

        boolean viewAll = Boolean.TRUE.equals(request.viewAll());
        Set<String> requestedRiderIds = viewAll || request.riderIds() == null
                ? Set.of()
                : new LinkedHashSet<>(request.riderIds());

        List<RiderInfo> selectedRiders = viewAll
                ? document.riders()
                : document.riders().stream().filter(r -> requestedRiderIds.contains(r.riderId())).toList();
        Set<String> selectedRiderIds = new LinkedHashSet<>();
        for (RiderInfo r : selectedRiders) {
            selectedRiderIds.add(r.riderId());
        }

        List<Clause> clauses = document.clauses().stream()
                .filter(c -> c.riderId() == null || selectedRiderIds.contains(c.riderId()))
                .toList();

        String analysisId = UUID.randomUUID().toString();
        // F-03(문서 종류 판별) 연동 전까지 DocumentRecord는 productName·versionLabel을
        // 채우지 않는다(= null). 결과 응답에도 같은 모양으로 그대로 null이 내려간다.
        AnalysisRecord queued = AnalysisRecord.queued(analysisId, document.documentId(), viewAll,
                null, null, document.pageCount(),
                selectedRiders, clauses);
        analysisStore.save(queued);

        analysisRunner.runAsync(analysisId);
        return queued;
    }

    public BulkExtractCoordinator.Progress progress(String analysisId) {
        if (analysisStore.get(analysisId) == null) throw ApiException.notFound("분석 작업을 찾을 수 없습니다.");
        return aiClient.extractProgress(analysisId);
    }

    public synchronized void resume(String analysisId) {
        AnalysisRecord record = analysisStore.get(analysisId);
        if (record == null) throw ApiException.notFound("분석 작업을 찾을 수 없습니다.");
        DocumentRecord document = documentStore.get(record.documentId());
        boolean beforeDispatch=aiClient.durableEnabled() && "ANALYSIS_INTERRUPTED".equals(record.errorCode())
                && aiClient.extractProgress(analysisId)==null;
        if (document == null || document.expiresAt()!=null && !document.expiresAt().isAfter(java.time.OffsetDateTime.now())
                || record.status() != AnalysisStatus.FAILED || !(beforeDispatch || aiClient.canResumeExtract(analysisId)))
            throw ApiException.notReady("재개 가능한 추출 체크포인트가 없습니다.");
        analysisStore.save(record.withStep(AnalysisStep.EXTRACTING));
        analysisRunner.runAsync(analysisId);
    }

    public boolean resumable(String id) {
        AnalysisRecord record=analysisStore.get(id);
        return record!=null && record.status()==AnalysisStatus.FAILED && (aiClient.canResumeExtract(id)
                || aiClient.durableEnabled() && "ANALYSIS_INTERRUPTED".equals(record.errorCode()) && aiClient.extractProgress(id)==null);
    }
}
