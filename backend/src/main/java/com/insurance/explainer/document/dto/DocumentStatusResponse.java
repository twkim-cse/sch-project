package com.insurance.explainer.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.mockapi.ApiError;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/documents/{documentId} 응답(명세서 4장).
 * 값이 비어있는 필드는 직렬화에서 빠진다(JsonInclude.NON_NULL).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentStatusResponse(
        String documentId,
        String status,
        String productName,
        String versionLabel,
        Integer pageCount,
        List<RiderInfo> riders,
        OffsetDateTime expiresAt,
        ApiError reject
) {
    public static DocumentStatusResponse ready(String documentId, int pageCount,
                                                List<RiderInfo> riders, OffsetDateTime expiresAt) {
        // F-03(문서 종류 판별)이 아직 가짜 응답이라 productName·versionLabel은 비워 둔다.
        return new DocumentStatusResponse(documentId, "READY", null, null,
                pageCount, riders, expiresAt, null);
    }

    public static DocumentStatusResponse rejected(String documentId, String code, String message) {
        return new DocumentStatusResponse(documentId, "REJECTED", null, null, null, null, null,
                new ApiError(code, message));
    }
}
