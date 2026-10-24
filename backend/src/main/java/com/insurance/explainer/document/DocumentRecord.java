package com.insurance.explainer.document;

import com.insurance.explainer.structure.Clause;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * F-02(입력 검사)·F-04(구조 분석)를 거쳐 만들어진 문서 한 건.
 * 글자 추출·구조 분석은 비동기로 처리되므로, 업로드 직후에는 PENDING(= 조회 응답의
 * CHECKING)으로 저장해 두고 처리가 끝나면 READY/REJECTED로 덮어쓴다.
 * F-03(문서 종류 판별)은 아직 AI가 가짜라서 항상 약관(TERMS)으로 보고, productName과
 * versionLabel은 채우지 않는다(= null, "찾지 못하면 null" 규칙과 같은 모양).
 */
public record DocumentRecord(
        String documentId,
        DocumentStatus status,
        Integer pageCount,
        List<RiderInfo> riders,
        List<Clause> clauses,
        OffsetDateTime expiresAt,
        String rejectCode,
        String rejectMessage
) {

    public static DocumentRecord pending(String documentId) {
        return new DocumentRecord(documentId, DocumentStatus.PENDING, null, null, List.of(),
                null, null, null);
    }

    public static DocumentRecord ready(String documentId, int pageCount, List<RiderInfo> riders,
                                        List<Clause> clauses, OffsetDateTime expiresAt) {
        return new DocumentRecord(documentId, DocumentStatus.READY, pageCount, riders, clauses,
                expiresAt, null, null);
    }

    public static DocumentRecord rejected(String documentId, String rejectCode, String rejectMessage) {
        return new DocumentRecord(documentId, DocumentStatus.REJECTED, null, null, List.of(),
                null, rejectCode, rejectMessage);
    }
}
