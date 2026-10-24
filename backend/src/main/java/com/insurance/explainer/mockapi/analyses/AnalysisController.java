package com.insurance.explainer.mockapi.analyses;

import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.mockapi.analyses.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 명세서 4장 프론트엔드 ⇄ 백엔드 API 중 분석 관련 3개.
 * 실제 분석은 하지 않고, 명세서의 예시 JSON을 고정 값으로 돌려주는 가짜(mock) 구현이다.
 */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AnalysisStore store;
    private final DocumentStore documentStore;

    public AnalysisController(AnalysisStore store, DocumentStore documentStore) {
        this.store = store;
        this.documentStore = documentStore;
    }

    // POST /api/analyses - 분석 시작
    @PostMapping
    public ResponseEntity<StartResponse> start(@RequestBody StartRequest request) {
        if (request.documentId() == null || !documentStore.exists(request.documentId())) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }

        String analysisId = UUID.randomUUID().toString();
        List<RiderInfo> selectedRiders = List.of(new RiderInfo("rider01", "암진단 특별약관", 85));

        store.save(new AnalysisRecord(analysisId, request.documentId(), request.riderIds(),
                Boolean.TRUE.equals(request.viewAll()), selectedRiders));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new StartResponse(analysisId, "QUEUED"));
    }

    // GET /api/analyses/{analysisId} - 진행 상태 (2~3초 간격 조회)
    @GetMapping("/{analysisId}")
    public StatusResponse getStatus(@PathVariable String analysisId) {
        AnalysisRecord record = store.get(analysisId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }

        int call = record.nextStatusCall();
        if (call > AnalysisRecord.STEPS.length) {
            return StatusResponse.done(analysisId);
        }
        String step = AnalysisRecord.STEPS[call - 1];
        return StatusResponse.running(analysisId, step, call, AnalysisRecord.STEPS.length);
    }

    // GET /api/analyses/{analysisId}/result - 분석 결과 (status가 DONE일 때)
    @GetMapping("/{analysisId}/result")
    public ResultResponse getResult(@PathVariable String analysisId) {
        AnalysisRecord record = store.get(analysisId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        if (!record.isDone()) {
            throw ApiException.notReady("완료 전 결과 요청");
        }

        return FixedResults.example(analysisId, record.viewAll());
    }
}
