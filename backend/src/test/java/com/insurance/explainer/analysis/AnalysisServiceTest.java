package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CardDto;
import com.insurance.explainer.analysis.ai.ExplainResponse;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.analysis.dto.StartRequest;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.Clause;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-08 분석 작업 관리를 검증한다. {@code new AnalysisService(...)}로 만든 일반
 * 객체는 Spring AOP 프록시가 없어 {@code @Async}가 적용되지 않으므로,
 * {@link AnalysisService#start}가 돌아왔을 때 {@link AnalysisStore}에는 이미
 * 최종 상태(DONE/FAILED)가 들어 있다(실제 비동기 동작은
 * {@link AnalysisControllerIntegrationTest}에서 확인한다).
 */
class AnalysisServiceTest {

    private final DocumentStore documentStore = new DocumentStore();
    private final AnalysisStore analysisStore = new AnalysisStore();
    private final FakeAiClient aiClient = new FakeAiClient();
    private final AnalysisService service = new AnalysisService(
            documentStore, analysisStore, aiClient,
            new PassthroughQuoteValidator(), new PassthroughNumberConsistencyChecker());

    @Test
    void 없는_문서면_NOT_FOUND() {
        StartRequest request = new StartRequest("no-such-doc", List.of(), false);

        assertThatThrownBy(() -> service.start(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.code()).isEqualTo("NOT_FOUND");
                    assertThat(apiException.status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void 문서가_READY가_아니면_NOT_READY() {
        documentStore.save(DocumentRecord.pending("doc-1"));
        StartRequest request = new StartRequest("doc-1", List.of(), false);

        assertThatThrownBy(() -> service.start(request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.code()).isEqualTo("NOT_READY");
                    assertThat(apiException.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void viewAll이_아니면_선택한_특약과_주계약_조항만_분석대상에_포함된다() {
        seedReadyDocument();
        aiClient.onExtract(req -> new ExtractResponse(List.of()));

        StartRequest request = new StartRequest("doc-1", List.of("rider01"), false);
        AnalysisRecord queued = service.start(request);

        AnalysisRecord finalRecord = analysisStore.get(queued.analysisId());
        assertThat(finalRecord.clauses()).extracting(Clause::clauseId)
                .containsExactlyInAnyOrder("main-art1", "rider01-art5-p1"); // rider02 조항은 빠짐
        assertThat(finalRecord.selectedRiders()).extracting(RiderInfo::riderId).containsExactly("rider01");
    }

    @Test
    void viewAll이면_모든_특약_조항이_분석대상에_포함된다() {
        seedReadyDocument();
        aiClient.onExtract(req -> new ExtractResponse(List.of()));

        StartRequest request = new StartRequest("doc-1", null, true);
        AnalysisRecord queued = service.start(request);

        AnalysisRecord finalRecord = analysisStore.get(queued.analysisId());
        assertThat(finalRecord.clauses()).extracting(Clause::clauseId)
                .containsExactlyInAnyOrder("main-art1", "rider01-art5-p1", "rider02-art8-p1");
        assertThat(finalRecord.selectedRiders()).extracting(RiderInfo::riderId)
                .containsExactly("rider01", "rider02");
    }

    @Test
    void 전체_파이프라인을_통과하면_DONE_상태로_카드와_찾지못함_항목이_채워진다() {
        seedReadyDocument();

        aiClient.onExtract(req -> new ExtractResponse(List.of(
                new ExtractionDto(2, "rider01-art5-p1", "90일이 지난날의 다음날로 합니다."),
                new ExtractionDto(1, "main-art1", "보험계약의 내용을 규정합니다.")
        )));
        aiClient.onExplain(req -> new ExplainResponse(req.items().stream()
                .map(item -> new CardDto(item.itemId(), "제목: " + item.checkItem(),
                        "해설: " + item.quote(), "pass", null))
                .toList()));

        StartRequest request = new StartRequest("doc-1", List.of("rider01"), false);
        AnalysisRecord queued = service.start(request);
        assertThat(queued.status()).isEqualTo(AnalysisStatus.QUEUED);

        AnalysisRecord finalRecord = analysisStore.get(queued.analysisId());
        assertThat(finalRecord.status()).isEqualTo(AnalysisStatus.DONE);
        assertThat(finalRecord.cards()).hasSize(2);

        CardResult mainCard = finalRecord.cards().stream()
                .filter(c -> c.checkItem() == 1).findFirst().orElseThrow();
        assertThat(mainCard.riderId()).isNull(); // 주계약
        assertThat(mainCard.importance()).isEqualTo("HIGH");
        assertThat(mainCard.display()).isEqualTo("FULL");
        assertThat(mainCard.title()).isEqualTo("제목: 1");

        CardResult riderCard = finalRecord.cards().stream()
                .filter(c -> c.checkItem() == 2).findFirst().orElseThrow();
        assertThat(riderCard.riderId()).isEqualTo("rider01");
        assertThat(riderCard.importance()).isEqualTo("HIGH");

        // 점검 항목 3~6은 카드가 없으므로 결과를 만들 때 NOT_FOUND로 표시돼야 한다.
        var result = com.insurance.explainer.analysis.dto.ResultResponseBuilder.build(finalRecord);
        assertThat(result.checkSummary()).hasSize(6);
        assertThat(result.checkSummary()).filteredOn(c -> c.checkItem() == 3)
                .extracting(c -> c.status()).containsExactly("NOT_FOUND");
        assertThat(result.checkSummary()).filteredOn(c -> c.checkItem() == 1)
                .extracting(c -> c.status()).containsExactly("FOUND");
    }

    @Test
    void 의미검토가_fail이면_QUOTE_ONLY로_표시되고_제목_해설은_null() {
        seedReadyDocument();
        aiClient.onExtract(req -> new ExtractResponse(List.of(
                new ExtractionDto(1, "main-art1", "보험계약의 내용을 규정합니다.")
        )));
        aiClient.onExplain(req -> new ExplainResponse(List.of(
                new CardDto(req.items().get(0).itemId(), "제목", "해설", "fail", "뜻이 달라짐")
        )));

        AnalysisRecord queued = service.start(new StartRequest("doc-1", List.of(), false));
        AnalysisRecord finalRecord = analysisStore.get(queued.analysisId());

        CardResult card = finalRecord.cards().get(0);
        assertThat(card.display()).isEqualTo("QUOTE_ONLY");
        assertThat(card.title()).isNull();
        assertThat(card.explanation()).isNull();
        assertThat(card.quote()).isNotBlank();
    }

    @Test
    void AI_호출이_재시도_후에도_실패하면_FAILED_ANALYSIS_FAILED로_끝난다() {
        seedReadyDocument();
        aiClient.onExtract(req -> {
            throw new RuntimeException("AI 서버 응답 없음");
        });

        AnalysisRecord queued = service.start(new StartRequest("doc-1", List.of(), false));
        AnalysisRecord finalRecord = analysisStore.get(queued.analysisId());

        assertThat(finalRecord.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(finalRecord.errorCode()).isEqualTo("ANALYSIS_FAILED");
    }

    private void seedReadyDocument() {
        List<RiderInfo> riders = List.of(
                new RiderInfo("rider01", "암진단 특별약관", 85),
                new RiderInfo("rider02", "입원특약", 120)
        );
        List<Clause> clauses = List.of(
                new Clause("main-art1", null, "주계약", "제1조", "목적", null, 1, false,
                        "이 약관은 보험계약의 내용을 규정합니다."),
                new Clause("rider01-art5-p1", "rider01", "암진단 특별약관", "제5조",
                        "보험금 지급에 관한 세부규정", "제1항", 87, false,
                        "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다."),
                new Clause("rider02-art8-p1", "rider02", "입원특약", "제8조",
                        "입원급부금의 지급", "제1항", 121, false,
                        "질병으로 4일 이상 계속 입원하였을 때 지급합니다.")
        );
        documentStore.save(DocumentRecord.ready("doc-1", 212, riders, clauses, OffsetDateTime.now()));
    }
}
