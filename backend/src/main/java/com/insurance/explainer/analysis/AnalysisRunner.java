package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CardDto;
import com.insurance.explainer.analysis.ai.ClauseDto;
import com.insurance.explainer.analysis.ai.ExplainItemDto;
import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.analysis.ai.RetryHint;
import com.insurance.explainer.structure.Clause;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 분석 단계(EXTRACTING → CHECKING_QUOTES → EXPLAINING → FINALIZING)를 실제로
 * 진행하는 비동기 작업자. {@link AnalysisService}와 별도 빈으로 둔 이유: 같은 빈
 * 안에서 {@code this.메서드()}로 자기 자신을 호출하면 Spring의 {@code @Async}
 * 프록시를 거치지 않아 비동기로 동작하지 않는다(self-invocation 문제). 그래서
 * {@link AnalysisService#start}가 이 빈을 (다른 빈으로서) 호출하도록 분리했다.
 * <p>
 * {@code MOCK_DOCUMENTS=true}일 때는 각 단계 사이에 짧게(기본 1~2초) 기다려서,
 * 프론트가 진행 화면(QUEUED→RUNNING 단계별→DONE)을 실제처럼 시험해 볼 수 있게 한다.
 * AI 호출 자체는 평소와 똑같이 한다(AI 서버는 고정 응답을 줄 뿐 실제로 호출된다).
 */
@Component
public class AnalysisRunner {

    private final AnalysisStore analysisStore;
    private final AiClient aiClient;
    private final QuoteValidator quoteValidator;
    private final NumberConsistencyChecker numberConsistencyChecker;
    private final boolean mockDocuments;
    private final long mockStepDelayMinMs;
    private final long mockStepDelayMaxMs;

    public AnalysisRunner(AnalysisStore analysisStore, AiClient aiClient,
                           QuoteValidator quoteValidator, NumberConsistencyChecker numberConsistencyChecker,
                           @Value("${app.mock-documents:false}") boolean mockDocuments,
                           @Value("${app.mock-analysis-step-delay-min-ms:1000}") long mockStepDelayMinMs,
                           @Value("${app.mock-analysis-step-delay-max-ms:2000}") long mockStepDelayMaxMs) {
        this.analysisStore = analysisStore;
        this.aiClient = aiClient;
        this.quoteValidator = quoteValidator;
        this.numberConsistencyChecker = numberConsistencyChecker;
        this.mockDocuments = mockDocuments;
        this.mockStepDelayMinMs = mockStepDelayMinMs;
        this.mockStepDelayMaxMs = mockStepDelayMaxMs;
    }

    @Async
    public void runAsync(String analysisId) {
        AnalysisRecord record = analysisStore.get(analysisId);
        try {
            Map<String, Clause> clauseById = new LinkedHashMap<>();
            for (Clause c : record.clauses()) {
                clauseById.put(c.clauseId(), c);
            }

            // 1) EXTRACTING: 위험 조항 추출 (F-09, /ai/extract)
            record = record.withStep(AnalysisStep.EXTRACTING);
            analysisStore.save(record);
            mockStepDelay();
            List<ExtractionDto> extractions = extract(record);

            // 2) CHECKING_QUOTES: 인용 대조 (F-10). quote와 조항 원문을 같은 규칙으로
            //    정리해 비교하고, 요청에 없던 clause_id를 인용한 것도 여기서 걸러진다
            //    (clauseById에 없으면 source가 null이 되어 실패 처리).
            record = record.withStep(AnalysisStep.CHECKING_QUOTES);
            analysisStore.save(record);
            mockStepDelay();
            List<ExtractionDto> validated = extractions.stream()
                    .filter(e -> {
                        Clause source = clauseById.get(e.clauseId());
                        return source != null && quoteValidator.isQuoteFoundInClause(e, source);
                    })
                    .toList();

            // 3) EXPLAINING: 쉬운 말 해설 (F-11/F-12/F-13, /ai/explain)
            record = record.withStep(AnalysisStep.EXPLAINING);
            analysisStore.save(record);
            mockStepDelay();
            // item_id ↔ 원본 조항(clauseId)을 끝까지 들고 가기 위한 보조 맵.
            // AI에는 item_id/check_item/section/article_title/quote만 보낸다(명세서 5장).
            Map<String, String> clauseIdByItemId = new LinkedHashMap<>();
            List<ExplainItemDto> items = buildExplainItems(validated, clauseById, clauseIdByItemId);
            List<CardDto> cards = explain(record, items);

            // 4) FINALIZING: 숫자 검사(F-14) + 의미검토(F-12) 반영 + 중요도 부여 +
            //    "찾지 못함" 채움 + 주계약/특약별로 묶어 저장
            record = record.withStep(AnalysisStep.FINALIZING);
            analysisStore.save(record);
            mockStepDelay();
            Map<String, ExplainItemDto> itemById = new LinkedHashMap<>();
            for (ExplainItemDto item : items) {
                itemById.put(item.itemId(), item);
            }
            List<CardResult> finalCards = finalize(record, cards, itemById, clauseIdByItemId, clauseById);

            analysisStore.save(record.done(finalCards));
        } catch (RuntimeException e) {
            analysisStore.save(record.failed("ANALYSIS_FAILED", "잠시 후 다시 시도하도록 안내"));
        }
    }

    private List<ExtractionDto> extract(AnalysisRecord record) {
        List<ClauseDto> clauseDtos = record.clauses().stream().map(this::toClauseDto).toList();
        List<Integer> checkItems = CheckItem.ALL.stream().map(CheckItem::id).toList();
        ExtractRequest request = new ExtractRequest(record.analysisId(), checkItems, clauseDtos);
        ExtractResponse response = aiClient.extract(request);
        return response.extractions();
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

    /**
     * F-14 숫자 일치 검사를 해서 실패한 카드만 retry_hint를 담아 explain을 한 번 더
     * 부른다(명세서 6장). 재시도 후에도 숫자가 안 맞거나, AI가 review를 fail로
     * 돌려줬다면(F-12) display를 QUOTE_ONLY로 바꾸고 title·explanation은 비운다.
     */
    private List<CardResult> finalize(AnalysisRecord record, List<CardDto> firstCards,
                                       Map<String, ExplainItemDto> itemById,
                                       Map<String, String> clauseIdByItemId, Map<String, Clause> clauseById) {
        Map<String, CardDto> cardByItemId = new LinkedHashMap<>();
        for (CardDto card : firstCards) {
            cardByItemId.put(card.itemId(), card);
        }

        Map<String, NumberCheckResult> numberChecks = new LinkedHashMap<>();
        List<ExplainItemDto> retryItems = new ArrayList<>();
        for (CardDto card : firstCards) {
            ExplainItemDto item = itemById.get(card.itemId());
            if (item == null) {
                continue;
            }
            NumberCheckResult check = numberConsistencyChecker.check(card.title(), card.explanation(), item.quote());
            numberChecks.put(card.itemId(), check);
            // review가 이미 fail이면 어차피 QUOTE_ONLY가 되므로 다시 부를 필요는 없다.
            if (!check.consistent() && !"fail".equals(card.review())) {
                retryItems.add(new ExplainItemDto(item.itemId(), item.checkItem(), item.section(),
                        item.articleTitle(), item.quote(), new RetryHint(check.unmatchedNumbers())));
            }
        }

        if (!retryItems.isEmpty()) {
            ExplainResponse retryResponse = aiClient.explain(new ExplainRequest(record.analysisId(), retryItems));
            for (CardDto retried : retryResponse.cards()) {
                cardByItemId.put(retried.itemId(), retried);
                ExplainItemDto item = itemById.get(retried.itemId());
                if (item != null) {
                    numberChecks.put(retried.itemId(),
                            numberConsistencyChecker.check(retried.title(), retried.explanation(), item.quote()));
                }
            }
        }

        List<CardResult> results = new ArrayList<>();
        for (CardDto card : cardByItemId.values()) {
            ExplainItemDto item = itemById.get(card.itemId());
            String clauseId = clauseIdByItemId.get(card.itemId());
            if (item == null || clauseId == null) {
                continue;
            }
            Clause source = clauseById.get(clauseId);
            if (source == null) {
                continue;
            }

            NumberCheckResult numberCheck = numberChecks.getOrDefault(card.itemId(), NumberCheckResult.ok());
            boolean reviewPassed = !"fail".equals(card.review());
            boolean fullDisplay = reviewPassed && numberCheck.consistent();

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

    private void mockStepDelay() {
        if (!mockDocuments) {
            return;
        }
        long range = Math.max(0, mockStepDelayMaxMs - mockStepDelayMinMs);
        long delay = mockStepDelayMinMs + (range == 0 ? 0 : ThreadLocalRandom.current().nextLong(range + 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
