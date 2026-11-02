package com.insurance.explainer.document;

import com.insurance.explainer.structure.Clause;

import java.util.List;

/**
 * MOCK_DOCUMENTS=true 일 때 쓰는 고정 응답. 실제 PDF 내용과 상관없이 명세서 4장
 * 예시와 같은 모양의 READY 결과를 돌려준다. 프론트가 실제 약관 없이 화면을 만들
 * 수 있도록 하기 위한 것으로, 입력 검사(F-02)와 구조 분석(F-04)을 완전히 건너뛴다.
 */
final class MockDocumentData {

    private MockDocumentData() {
    }

    /** pending의 documentId·expiresAt(F-21 보관 기한)을 그대로 이어받아 READY로 만든다. */
    static DocumentRecord ready(DocumentRecord pending) {
        List<RiderInfo> riders = List.of(new RiderInfo("rider01", "암진단 특별약관", 85));
        Clause clause = new Clause(
                "rider01-art5-p2",
                "rider01",
                "암진단 특별약관",
                "제5조",
                "보험금 지급에 관한 세부규정",
                "제2항",
                87,
                false,
                "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다."
        ); // clauseId, riderId, section, article, articleTitle, paragraph, page, fromTable, text
        return pending.withReady(212, riders, List.of(clause));
    }
}
