package com.insurance.explainer.document;

import com.insurance.explainer.document.dto.ClauseResponse;
import com.insurance.explainer.document.dto.DocumentStatusResponse;
import com.insurance.explainer.document.dto.UploadResponse;
import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.Clause;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 명세서 4장 프론트엔드 ⇄ 백엔드 API 중 문서 관련 4개.
 * F-02(입력 검사)·F-04(구조 분석)로 실제 분석한 결과를 돌려준다.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentStore store;
    private final DocumentUploadService uploadService;

    public DocumentController(DocumentStore store, DocumentUploadService uploadService) {
        this.store = store;
        this.uploadService = uploadService;
    }

    // POST /api/documents - 약관 PDF 업로드. 처리는 이 안에서 동기적으로 끝나지만,
    // 명세서대로 응답은 항상 202 CHECKING이다(실제 상태는 뒤이은 GET에서 확인).
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        DocumentRecord record = uploadService.process(file);
        store.save(record);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponse(record.documentId(), "CHECKING"));
    }

    // GET /api/documents/{documentId} - 문서 확인 상태와 특약 목록
    @GetMapping("/{documentId}")
    public DocumentStatusResponse getStatus(@PathVariable String documentId) {
        DocumentRecord record = store.get(documentId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        if (record.status() == DocumentStatus.REJECTED) {
            return DocumentStatusResponse.rejected(documentId, record.rejectCode(), record.rejectMessage());
        }
        return DocumentStatusResponse.ready(documentId, record.pageCount(), record.riders(), record.expiresAt());
    }

    // GET /api/documents/{documentId}/clauses/{clauseId} - 원문 보기
    @GetMapping("/{documentId}/clauses/{clauseId}")
    public ClauseResponse getClause(@PathVariable String documentId, @PathVariable String clauseId) {
        DocumentRecord record = store.get(documentId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        Clause clause = record.clauses().stream()
                .filter(c -> c.clauseId().equals(clauseId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("다시 올려 달라고 안내"));
        return ClauseResponse.from(clause);
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
