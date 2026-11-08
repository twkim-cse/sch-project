package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CandidateMergeRequest;
import com.insurance.explainer.analysis.ai.CandidateMergeResponse;
import com.insurance.explainer.analysis.ai.CandidatePartDto;
import com.insurance.explainer.analysis.ai.CandidatePlanRequest;
import com.insurance.explainer.analysis.ai.CandidatePlanResponse;
import com.insurance.explainer.analysis.ai.CardDto;
import com.insurance.explainer.analysis.ai.ClauseDto;
import com.insurance.explainer.analysis.ai.ExplainItemDto;
import com.insurance.explainer.analysis.ai.ExplainRequest;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.analysis.ai.PartResultDto;
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
import java.util.stream.Collectors;

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

    // 후보 필터링을 쓰지 않고 전체 조항을 대상으로 한다("mode":"recall"은 AI
    // 서버가 항목별 근거가 없으면 자동으로 넓게 재검색하므로, 명시적인
    // broad/full 승격은 지금은 두지 않는다. 명세서 5장/candidates 연동 문서 참고).
    private static final String CANDIDATE_MODE = "recall";

    /**
     * 조항이 많은 약관은 기존 /ai/extract 한 번으로 보내면 배치/예산 초과(413)가
     * 난다. 그래서 AI 서버의 무손실 분할 계획(candidates/plan)을 먼저 받아
     * 조각(part)별로 나눠 순서대로 /ai/extract를 부르고, 끝나면 candidates/merge로
     * 원래 조항 기준 결과를 되돌려 받는다(명세서 5장, candidates 연동 문서).
     * <p>
     * 조각 하나가 (AI 서버가 이미 내부 재시도·cooldown을 책임지는 상태에서도)
     * 실패하면, 성공한 조각까지는 체크포인트에 남기고 즉시 분석을 중단한다.
     * 실패한 조각을 빈 결과로 채워 계속 진행하면 그 조각의 조항들이 전부 F-17
     * "찾지 못함"으로 둔갑해 버려 실제로는 분석되지 않은 범위를 "위험 없음"처럼
     * 보여줄 위험이 있다(이는 안전하지 않은 동작이라 더는 쓰지 않는다). merge/
     * explain은 모든 조각이 성공했을 때만 진행한다.
     */
    private List<ExtractionDto> extract(AnalysisRecord record) {
        List<ClauseDto> clauseDtos = record.clauses().stream().map(this::toClauseDto).toList();
        List<Integer> allCheckItemIds = CheckItem.ALL.stream().map(CheckItem::id).toList();
        ExtractRequest sourceRequest = new ExtractRequest(record.analysisId(), allCheckItemIds, clauseDtos);
        CandidatePlanRequest planRequest = new CandidatePlanRequest(sourceRequest, CANDIDATE_MODE, null);

        CandidatePlanResponse plan = aiClient.candidatesPlan(planRequest);
        List<CandidatePartDto> parts = plan.candidatePlan().parts();

        Map<String, ExtractResponse> responseByPartId = new LinkedHashMap<>();
        analysisStore.candidateCheckpoint(record.analysisId(),
                new CandidateCheckpoint(planRequest, plan, responseByPartId, null, "PLANNED",
                        plan.selection().coverage()));
        for (CandidatePartDto part : parts) {
            try {
                responseByPartId.put(part.partId(), aiClient.extract(part.request()));
                analysisStore.candidateCheckpoint(record.analysisId(),
                        new CandidateCheckpoint(planRequest, plan, responseByPartId, null, "RUNNING",
                                plan.selection().coverage()));
            } catch (RuntimeException partFailure) {
                String state = responseByPartId.isEmpty() ? "FAILED" : "PARTIAL";
                analysisStore.candidateCheckpoint(record.analysisId(),
                        new CandidateCheckpoint(planRequest, plan, responseByPartId, part.partId(), state,
                                plan.selection().coverage()));
                throw partFailure;
            }
        }

        List<PartResultDto> partResults = parts.stream()
                .map(part -> new PartResultDto(part.partId(), responseByPartId.get(part.partId())))
                .toList();
        CandidateMergeRequest mergeRequest = new CandidateMergeRequest(sourceRequest, CANDIDATE_MODE, null,
                plan.selection().selectionId(), plan.candidatePlan().planId(), partResults);
        try {
            CandidateMergeResponse merged = aiClient.candidatesMerge(mergeRequest);
            analysisStore.candidateCheckpoint(record.analysisId(),
                    new CandidateCheckpoint(planRequest, plan, responseByPartId, null, "CANDIDATES_COMPLETE",
                            merged.coverage()));
            return merged.extractions();
        } catch (RuntimeException mergeFailure) {
            analysisStore.candidateCheckpoint(record.analysisId(),
                    new CandidateCheckpoint(planRequest, plan, responseByPartId, null, "MERGE_FAILED",
                            plan.selection().coverage()));
            throw mergeFailure;
        }
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

    // AI 설명 요청 배치가 실패했을 때, 원문(quote)만 보여주고 제목·해설은 비우는
    // 이유(F-12 "검토 실패"와 같은 QUOTE_ONLY 표시를 그대로 재사용한다).
    private static final String EXPLAIN_BATCH_FAILED_REASON = "AI 설명 요청이 실패해 원문만 보여줍니다.";

    // AI 서버가 항목 하나당 초안 생성·의미 독립검토 2번씩 Gemini를 부르므로,
    // 요청 하나에 넣는 항목이 많을수록 그 요청의 생성 호출 예산(명세서 5장)을
    // 넘기기 쉽다. 그래서 두 항목씩 순서대로 나눠 보낸다.
    private static final int EXPLAIN_BATCH_SIZE = 2;

    /**
     * 해설이 필요한 항목을 {@link #EXPLAIN_BATCH_SIZE}개씩 순서대로 묶어 요청한다.
     * 묶음 하나가 실패하면(extract와 같은 이유) 점검 항목별로 나눠 다시 시도한다.
     * 그래도 실패하는 점검 항목은 review를 "fail"로 둬서, finalize에서 기존 F-12
     * 로직대로 QUOTE_ONLY(원문만 표시)로 처리되게 한다. 성공한 점검 항목은 AI가
     * 돌려준 제목·해설을 그대로 쓴다.
     */
    private List<CardDto> explain(AnalysisRecord record, List<ExplainItemDto> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        if (items.size() > EXPLAIN_BATCH_SIZE) {
            List<CardDto> result = new ArrayList<>();
            for (int offset = 0; offset < items.size(); offset += EXPLAIN_BATCH_SIZE) {
                int end = Math.min(offset + EXPLAIN_BATCH_SIZE, items.size());
                result.addAll(explain(record, items.subList(offset, end)));
            }
            return result;
        }
        ExplainRequest wholeRequest = new ExplainRequest(record.analysisId(), items);
        try {
            return aiClient.explain(wholeRequest).cards();
        } catch (RuntimeException wholeFailure) {
            Map<Integer, List<ExplainItemDto>> itemsByCheckItem = items.stream()
                    .collect(Collectors.groupingBy(ExplainItemDto::checkItem, LinkedHashMap::new, Collectors.toList()));
            List<CardDto> partial = new ArrayList<>();
            for (List<ExplainItemDto> group : itemsByCheckItem.values()) {
                try {
                    partial.addAll(aiClient.explain(new ExplainRequest(record.analysisId(), group)).cards());
                } catch (RuntimeException groupFailure) {
                    for (ExplainItemDto item : group) {
                        partial.add(new CardDto(item.itemId(), null, null, "fail", EXPLAIN_BATCH_FAILED_REASON));
                    }
                }
            }
            return partial;
        }
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
