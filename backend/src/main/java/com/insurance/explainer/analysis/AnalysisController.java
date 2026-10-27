package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.dto.ResultResponse;
import com.insurance.explainer.analysis.dto.ResultResponseBuilder;
import com.insurance.explainer.analysis.dto.StartRequest;
import com.insurance.explainer.analysis.dto.StartResponse;
import com.insurance.explainer.analysis.dto.StatusResponse;
import com.insurance.explainer.mockapi.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 명세서 4장 프론트엔드 ⇄ 백엔드 API 중 분석 관련 3개(F-08).
 */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisStore analysisStore;

    public AnalysisController(AnalysisService analysisService, AnalysisStore analysisStore) {
        this.analysisService = analysisService;
        this.analysisStore = analysisStore;
    }

    // POST /api/analyses - 분석 시작
    @PostMapping
    public ResponseEntity<StartResponse> start(@RequestBody StartRequest request) {
        AnalysisRecord record = analysisService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new StartResponse(record.analysisId(), "QUEUED"));
    }

    // GET /api/analyses/{analysisId} - 진행 상태 (2~3초 간격 조회)
    @GetMapping("/{analysisId}")
    public StatusResponse getStatus(@PathVariable String analysisId) {
        AnalysisRecord record = analysisStore.get(analysisId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        return switch (record.status()) {
            case QUEUED -> StatusResponse.queued(analysisId);
            case RUNNING -> {
                int stepIndex = record.step().ordinal() + 1;
                yield StatusResponse.running(analysisId, record.step().name(), stepIndex,
                        AnalysisStep.values().length);
            }
            case DONE -> StatusResponse.done(analysisId);
            case FAILED -> StatusResponse.failed(analysisId, record.errorCode(), record.errorMessage());
        };
    }

    // GET /api/analyses/{analysisId}/result - 분석 결과 (status가 DONE일 때)
    @GetMapping("/{analysisId}/result")
    public ResultResponse getResult(@PathVariable String analysisId) {
        AnalysisRecord record = analysisStore.get(analysisId);
        if (record == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        if (record.status() != AnalysisStatus.DONE) {
            throw ApiException.notReady("완료 전 결과 요청");
        }
        return ResultResponseBuilder.build(record);
    }
}
