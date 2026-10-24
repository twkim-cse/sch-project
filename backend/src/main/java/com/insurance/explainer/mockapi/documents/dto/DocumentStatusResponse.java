package com.insurance.explainer.mockapi.documents.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.explainer.mockapi.ApiError;
import com.insurance.explainer.mockapi.documents.RiderInfo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/documents/{documentId} 응답(명세서 4장).
 * CHECKING일 때는 documentId·status만 내려가도록, 값이 비어있는 필드는
 * 직렬화에서 빠지게 한다(JsonInclude.NON_NULL).
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
    public static DocumentStatusResponse checking(String documentId) {
        return new DocumentStatusResponse(documentId, "CHECKING", null, null, null, null, null, null);
    }

    public static DocumentStatusResponse ready(String documentId, String productName, String versionLabel,
                                                int pageCount, List<RiderInfo> riders, OffsetDateTime expiresAt) {
        return new DocumentStatusResponse(documentId, "READY", productName, versionLabel,
                pageCount, riders, expiresAt, null);
    }
}
