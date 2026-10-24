package com.insurance.explainer.mockapi.analyses.dto;

import java.util.List;

/**
 * GET /api/analyses/{analysisId}/result 의 고정(mock) 응답을 만든다.
 * 명세서 4장 예시 JSON을 그대로 옮긴 값이다.
 */
public final class FixedResults {

    private FixedResults() {
    }

    public static ResultResponse example(String analysisId, boolean viewAll) {
        var document = new DocumentSummary("(무)○○암보험", "2025년 4월 개정", 212);
        var selectedRiders = List.of(new SelectedRider("rider01", "암진단 특별약관"));

        var checkSummary = List.of(
                new CheckSummaryItem(1, "보험금을 주지 않는 경우", "FOUND", 4),
                new CheckSummaryItem(2, "기다려야 하는 기간", "FOUND", 2),
                new CheckSummaryItem(3, "보험료가 오를 수 있음", "NOT_FOUND", 0),
                new CheckSummaryItem(4, "알릴 의무", "NOT_FOUND", 0),
                new CheckSummaryItem(5, "약관이 정한 뜻", "NOT_FOUND", 0),
                new CheckSummaryItem(6, "중간에 해지하면", "NOT_FOUND", 0)
        );

        var card = new ResultCard(
                "c-017",
                2,
                "HIGH",
                "FULL",
                "가입하고 90일 안에 암 진단을 받으면 보험금이 나오지 않습니다",
                "암에 대한 보장은 계약한 날을 포함해 90일이 지난 다음 날부터 시작됩니다.",
                "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.",
                new ClauseSource("암진단 특별약관", "제5조", "제2항", 87),
                "rider01-art5-p2",
                false
        );

        var groups = List.of(
                new ResultGroup("MAIN", null, "공통(주계약)", List.of()),
                new ResultGroup("RIDER", "rider01", "암진단 특별약관", List.of(card))
        );

        return new ResultResponse(analysisId, document, viewAll, selectedRiders, checkSummary, groups);
    }
}
