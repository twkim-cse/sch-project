package com.insurance.explainer.mockapi.documents;

import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.mockapi.documents.dto.ClauseResponse;
import com.insurance.explainer.mockapi.documents.dto.DocumentStatusResponse;
import com.insurance.explainer.mockapi.documents.dto.UploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 명세서 4장 프론트엔드 ⇄ 백엔드 API 중 문서 관련 4개.
 * 실제 분석은 하지 않고, 명세서의 예시 JSON을 고정 값으로 돌려주는 가짜(mock) 구현이다.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentStore store;

    public DocumentController(DocumentStore store) {
        this.store = store;
    }

    // POST /api/documents - 약관 PDF 업로드
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        String documentId = UUID.randomUUID().toString();

        List<RiderInfo> riders = List.of(
                new RiderInfo("rider01", "암진단 특별약관", 85)
        );
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(24);

        store.save(new DocumentRecord(documentId, "(무)○○암보험", "2025년 4월 개정",
                212, riders, expiresAt));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponse(documentId, "CHECKING"));
    }

    // GET /api/documents/{documentId} - 문서 확인 상태와 특약 목록
    @GetMapping("/{documentId}")
    public DocumentStatusResponse getStatus(@PathVariable String documentId) {
        DocumentRecord record = store.get(documentId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }

        if (record.isStillChecking()) {
            return DocumentStatusResponse.checking(documentId);
        }
        return DocumentStatusResponse.ready(documentId, record.productName(), record.versionLabel(),
                record.pageCount(), record.riders(), record.expiresAt());
    }

    // GET /api/documents/{documentId}/clauses/{clauseId} - 원문 보기
    @GetMapping("/{documentId}/clauses/{clauseId}")
    public ClauseResponse getClause(@PathVariable String documentId, @PathVariable String clauseId) {
        if (!store.exists(documentId)) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        return new ClauseResponse(
                clauseId,
                "암진단 특별약관",
                "제5조",
                "보험금 지급에 관한 세부규정",
                "제2항",
                87,
                "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다."
        );
    }

    // DELETE /api/documents/{documentId} - 파일과 분석 결과 즉시 삭제
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable String documentId) {
        if (!store.exists(documentId)) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        store.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
