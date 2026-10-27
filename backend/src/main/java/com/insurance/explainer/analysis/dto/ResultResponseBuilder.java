package com.insurance.explainer.analysis.dto;

import com.insurance.explainer.analysis.AnalysisRecord;
import com.insurance.explainer.analysis.CardResult;
import com.insurance.explainer.analysis.CheckItem;
import com.insurance.explainer.document.RiderInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DONE 상태의 {@link AnalysisRecord}를 GET .../result 응답 모양(명세서 4장)으로 바꾼다.
 * - checkSummary에는 항상 6개 항목이 모두 들어간다(F-17 "찾지 못함").
 * - 카드가 하나도 없는 항목은 NOT_FOUND.
 * - 카드는 공통(주계약)과 특약별로 묶고, 그룹 안에서는 점검 항목 순으로 둔다(F-15).
 */
public final class ResultResponseBuilder {

    private ResultResponseBuilder() {
    }

    public static ResultResponse build(AnalysisRecord record) {
        List<CardResult> cards = record.cards();

        Map<Integer, Long> cardCountByCheckItem = new LinkedHashMap<>();
        for (CardResult card : cards) {
            cardCountByCheckItem.merge(card.checkItem(), 1L, Long::sum);
        }
        List<CheckSummaryItem> checkSummary = CheckItem.ALL.stream()
                .map(item -> {
                    long count = cardCountByCheckItem.getOrDefault(item.id(), 0L);
                    String status = count > 0 ? "FOUND" : "NOT_FOUND";
                    return new CheckSummaryItem(item.id(), item.name(), status, (int) count);
                })
                .toList();

        List<ResultGroup> groups = new ArrayList<>();
        groups.add(buildGroup("MAIN", null, "공통(주계약)", cards));
        for (RiderInfo rider : record.selectedRiders()) {
            groups.add(buildGroup("RIDER", rider.riderId(), rider.name(), cards));
        }

        DocumentSummary document = new DocumentSummary(
                record.productName(), record.versionLabel(),
                record.pageCount() == null ? 0 : record.pageCount());
        List<SelectedRider> selectedRiders = record.selectedRiders().stream()
                .map(r -> new SelectedRider(r.riderId(), r.name()))
                .toList();

        return new ResultResponse(record.analysisId(), document, record.viewAll(), selectedRiders,
                checkSummary, groups);
    }

    private static ResultGroup buildGroup(String scope, String riderId, String name, List<CardResult> allCards) {
        List<ResultCard> groupCards = allCards.stream()
                .filter(c -> riderId == null ? c.riderId() == null : riderId.equals(c.riderId()))
                .sorted(Comparator.comparingInt(CardResult::checkItem))
                .map(ResultResponseBuilder::toResultCard)
                .toList();
        return new ResultGroup(scope, riderId, name, groupCards);
    }

    private static ResultCard toResultCard(CardResult c) {
        ClauseSource source = new ClauseSource(c.section(), c.article(), c.paragraph(), c.page());
        return new ResultCard(c.cardId(), c.checkItem(), c.importance(), c.display(),
                c.title(), c.explanation(), c.quote(), source, c.clauseId(), c.fromTable());
    }
}
