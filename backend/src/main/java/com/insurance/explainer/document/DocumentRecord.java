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
 * <p>
 * expiresAt(F-21 자동 삭제 시각)은 업로드 시점(PENDING으로 처음 저장될 때)에 한 번만
 * 정해지고, READY/REJECTED로 바뀌어도 그대로 이어진다(= 문서 조회 응답의 expiresAt이
 * 실제 삭제 시각과 항상 일치한다). {@link #withReady}·{@link #withRejected}로 상태를
 * 옮길 때 이 값을 그대로 들고 간다.
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

    public static DocumentRecord pending(String documentId, OffsetDateTime expiresAt) {
        return new DocumentRecord(documentId, DocumentStatus.PENDING, null, null, List.of(),
                expiresAt, null, null);
    }

    public DocumentRecord withReady(int pageCount, List<RiderInfo> riders, List<Clause> clauses) {
        return new DocumentRecord(documentId, DocumentStatus.READY, pageCount, riders, clauses,
                expiresAt, null, null);
    }

    public DocumentRecord withRejected(String rejectCode, String rejectMessage) {
        return new DocumentRecord(documentId, DocumentStatus.REJECTED, null, List.of(), List.of(),
                expiresAt, rejectCode, rejectMessage);
    }
}
