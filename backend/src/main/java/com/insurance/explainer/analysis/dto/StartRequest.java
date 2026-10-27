package com.insurance.explainer.analysis.dto;

import java.util.List;

// POST /api/analyses 요청(명세서 4장).
public record StartRequest(String documentId, List<String> riderIds, Boolean viewAll) {
}
