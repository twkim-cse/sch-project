package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CardDto;
import com.insurance.explainer.analysis.ai.ClauseDto;
import com.insurance.explainer.analysis.ai.ExplainItemDto;
import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.analysis.dto.StartRequest;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStatus;
import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.Clause;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F-08 분석 작업 관리.
 * <p>
 * {@link #start}는 문서가 READY인지 확인하고, 주계약 + 선택한 특약(또는 viewAll이면
 * 전체)의 조항만 골라 QUEUED 상태로 저장한 뒤 바로 202로 끝난다. 실제 단계
 * (EXTRACTING → CHECKING_QUOTES → EXPLAINING → FINALIZING)는 {@link #runAnalysis}가
 * 비동기로 진행하며, 단계가 바뀔 때마다 {@link AnalysisStore}에 진행 상태를 덮어쓴다.
 */
@Service
public class AnalysisService {

    private final DocumentStore documentStore;
    private final AnalysisStore analysisStore;
    private final AiClient aiClient;
    private final QuoteValidator quoteValidator;
    private final NumberConsistencyChecker numberConsistencyChecker;

    public AnalysisService(DocumentStore documentStore, AnalysisStore analysisStore, AiClient aiClient,
                            QuoteValidator quoteValidator, NumberConsistencyChecker numberConsistencyChecker) {
        this.documentStore = documentStore;
        this.analysisStore = analysisStore;
        this.aiClient = aiClient;
        this.quoteValidator = quoteValidator;
        this.numberConsistencyChecker = numberConsistencyChecker;
    }

    public AnalysisRecord start(StartRequest request) {
        DocumentRecord document = documentStore.get(request.documentId());
        if (document == null) {
            throw ApiException.notFound("다시 올려 달라고 안내");
        }
        if (document.status() != DocumentStatus.READY) {
            throw ApiException.notReady("문서 확인 전 분석 요청");
        }

        boolean viewAll = Boolean.TRUE.equals(request.viewAll());
        Set<String> requestedRiderIds = viewAll || request.riderIds() == null
                ? Set.of()
                : new LinkedHashSet<>(request.riderIds());

        List<RiderInfo> selectedRiders = viewAll
                ? document.riders()
                : document.riders().stream().filter(r -> requestedRiderIds.contains(r.riderId())).toList();
        Set<String> selectedRiderIds = new LinkedHashSet<>();
        for (RiderInfo r : selectedRiders) {
            selectedRiderIds.add(r.riderId());
        }

        List<Clause> clauses = document.clauses().stream()
                .filter(c -> c.riderId() == null || selectedRiderIds.contains(c.riderId()))
                .toList();

        String analysisId = UUID.randomUUID().toString();
        // F-03(문서 종류 판별) 연동 전까지 DocumentRecord는 productName·versionLabel을
        // 채우지 않는다(= null). 결과 응답에도 같은 모양으로 그대로 null이 내려간다.
        AnalysisRecord queued = AnalysisRecord.queued(analysisId, document.documentId(), viewAll,
                null, null, document.pageCount(),
                selectedRiders, clauses);
        analysisStore.save(queued);

        runAnalysis(analysisId);
        return queued;
    }

    @Async
    public void runAnalysis(String analysisId) {
        AnalysisRecord record = analysisStore.get(analysisId);
        try {
            Map<String, Clause> clauseById = new LinkedHashMap<>();
            for (Clause c : record.clauses()) {
                clauseById.put(c.clauseId(), c);
            }

            // 1) EXTRACTING: 위험 조항 추출 (F-09, /ai/extract)
            record = record.withStep(AnalysisStep.EXTRACTING);
            analysisStore.save(record);
            List<ExtractionDto> extractions = extract(record, clauseById);

            // 2) CHECKING_QUOTES: 인용 대조 (F-10, 지금은 통과만 시키는 자리)
            record = record.withStep(AnalysisStep.CHECKING_QUOTES);
            analysisStore.save(record);
            List<ExtractionDto> validated = extractions.stream()
                    .filter(e -> {
                        Clause source = clauseById.get(e.clauseId());
                        return source != null && quoteValidator.isQuoteFoundInClause(e, source);
                    })
                    .toList();

            // 3) EXPLAINING: 쉬운 말 해설 (F-11/F-12/F-13, /ai/explain)
            record = record.withStep(AnalysisStep.EXPLAINING);
            analysisStore.save(record);
            // item_id ↔ 원본 조항(clauseId)을 끝까지 들고 가기 위한 보조 맵.
            // AI에는 item_id/check_item/section/article_title/quote만 보낸다(명세서 5장).
            Map<String, String> clauseIdByItemId = new LinkedHashMap<>();
            List<ExplainItemDto> items = buildExplainItems(validated, clauseById, clauseIdByItemId);
            List<CardDto> cards = explain(record, items);

            // 4) FINALIZING: 숫자 검사(F-14, 지금은 통과만 시키는 자리) + 중요도 부여 +
            //    "찾지 못함" 채움 + 주계약/특약별로 묶어 저장
            record = record.withStep(AnalysisStep.FINALIZING);
            analysisStore.save(record);
            Map<String, ExplainItemDto> itemById = new LinkedHashMap<>();
            for (ExplainItemDto item : items) {
                itemById.put(item.itemId(), item);
            }
            List<CardResult> finalCards = finalize(cards, itemById, clauseIdByItemId, clauseById);

            analysisStore.save(record.done(finalCards));
        } catch (RuntimeException e) {
            analysisStore.save(record.failed("ANALYSIS_FAILED", "잠시 후 다시 시도하도록 안내"));
        }
    }

    private List<ExtractionDto> extract(AnalysisRecord record, Map<String, Clause> clauseById) {
        List<ClauseDto> clauseDtos = record.clauses().stream().map(this::toClauseDto).toList();
        List<Integer> checkItems = CheckItem.ALL.stream().map(CheckItem::id).toList();
        ExtractRequest request = new ExtractRequest(record.analysisId(), checkItems, clauseDtos);
        ExtractResponse response = aiClient.extract(request);
        return response.extractions().stream()
                .filter(e -> clauseById.containsKey(e.clauseId()))
                .toList();
    }

    private List<ExplainItemDto> buildExplainItems(List<ExtractionDto> validated,
                                                     Map<String, Clause> clauseById,
                                                     Map<String, String> clauseIdByItemId) {
        List<ExplainItemDto> items = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(1);
        for (ExtractionDto e : validated) {
            Clause source = clauseById.get(e.clauseId());
            String itemId = "c-%03d".formatted(seq.getAndIncrement());
            clauseIdByItemId.put(itemId, source.clauseId());
            items.add(new ExplainItemDto(itemId, e.checkItem(), source.section(),
                    source.articleTitle(), e.quote(), null));
        }
        return items;
    }

    private List<CardDto> explain(AnalysisRecord record, List<ExplainItemDto> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        ExplainRequest request = new ExplainRequest(record.analysisId(), items);
        ExplainResponse response = aiClient.explain(request);
        return response.cards();
    }

    private List<CardResult> finalize(List<CardDto> cards, Map<String, ExplainItemDto> itemById,
                                       Map<String, String> clauseIdByItemId, Map<String, Clause> clauseById) {
        List<CardResult> results = new ArrayList<>();
        for (CardDto card : cards) {
            ExplainItemDto item = itemById.get(card.itemId());
            String clauseId = clauseIdByItemId.get(card.itemId());
            if (item == null || clauseId == null) {
                continue;
            }
            Clause source = clauseById.get(clauseId);
            if (source == null) {
                continue;
            }

            boolean numbersOk = numberConsistencyChecker.isConsistent(card, item.quote());
            boolean reviewPassed = !"fail".equals(card.review());
            boolean fullDisplay = reviewPassed && numbersOk;

            CheckItem checkItem = CheckItem.byId(item.checkItem());
            results.add(new CardResult(
                    card.itemId(),
                    item.checkItem(),
                    source.riderId(),
                    checkItem.importance(),
                    fullDisplay ? "FULL" : "QUOTE_ONLY",
                    fullDisplay ? card.title() : null,
                    fullDisplay ? card.explanation() : null,
                    item.quote(),
                    source.section(),
                    source.article(),
                    source.paragraph(),
                    source.page(),
                    source.clauseId(),
                    source.fromTable()
            ));
        }
        return results;
    }

    private ClauseDto toClauseDto(Clause c) {
        return new ClauseDto(c.clauseId(), c.riderId(), c.section(), c.article(), c.articleTitle(),
                c.paragraph(), c.page(), c.fromTable(), c.text());
    }
}
