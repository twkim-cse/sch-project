package com.insurance.explainer.retention;

import com.insurance.explainer.analysis.AnalysisStore;
import com.insurance.explainer.document.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * F-21 자동 삭제.
 * <p>
 * 업로드한 파일(쪽별 글자)과 조항 데이터는 {@code DocumentStore}에, 분석 결과는
 * {@code AnalysisStore}에 들어 있다. 둘 다 인메모리 저장소라 여기서 지우면
 * 글자·조항·결과가 전부 함께 없어진다(별도로 디스크에 PDF를 저장하지 않으므로
 * 지울 파일이 따로 없다 — {@link #purgeExpired}의 클래스 설명 참고).
 * <p>
 * - {@link #deleteDocumentAndAnalyses}: 사용자가 직접 삭제(DELETE /api/documents/{id})할 때 즉시 호출
 * - {@link #purgeExpired}: 보관 기한(expiresAt)이 지난 문서를 주기적으로 찾아 같은 방식으로 지움
 */
@Service
public class DocumentRetentionService {

    private final DocumentStore documentStore;
    private final AnalysisStore analysisStore;

    public DocumentRetentionService(DocumentStore documentStore, AnalysisStore analysisStore) {
        this.documentStore = documentStore;
        this.analysisStore = analysisStore;
    }

    /** 문서와 그 문서로 만든 분석 결과를 전부 지운다. */
    public void deleteDocumentAndAnalyses(String documentId) {
        for (String analysisId : analysisStore.findIdsByDocumentId(documentId)) {
            analysisStore.delete(analysisId);
        }
        documentStore.delete(documentId);
    }

    /**
     * 보관 기한이 지난 문서를 찾아 지운다. 업로드된 PDF 원본은 메모리(byte[])에서만
     * 잠깐 쓰이고 디스크에 저장하지 않으므로, 글자 추출이 끝나는 즉시(해당 비동기
     * 작업이 끝나는 순간) 더 이상 참조가 남지 않아 가비지 컬렉션 대상이 된다 —
     * 별도로 지울 파일이 없다. 디스크 저장 방식으로 바꾸게 되면 이 메서드에서
     * 문서별 업로드 파일도 함께 지워야 한다.
     */
    @Scheduled(fixedDelayString = "${app.document-retention-sweep-ms:30000}",
            initialDelayString = "${app.document-retention-sweep-ms:30000}")
    public void purgeExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        for (String documentId : documentStore.findExpiredIds(now)) {
            deleteDocumentAndAnalyses(documentId);
        }
    }
}
