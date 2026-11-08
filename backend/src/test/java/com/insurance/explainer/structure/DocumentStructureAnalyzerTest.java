package com.insurance.explainer.structure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStructureAnalyzerTest {

    private final DocumentStructureAnalyzer analyzer = new DocumentStructureAnalyzer();

    @Test
    void 주계약_조와_항을_조항단위로_분할한다() {
        String page = """
                제5조(보험금 지급에 관한 세부규정)
                ① 암보장개시일은 계약일부터 그 날을 포함하여 90일이
                지난날의 다음날로 합니다.
                ② 회사는 보험수익자에게 보험금을 지급합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(87, page)));

        assertThat(result.clauses()).hasSize(2);
        Clause first = result.clauses().get(0);
        assertThat(first.clauseId()).isEqualTo("main-art5-p1");
        assertThat(first.riderId()).isNull();
        assertThat(first.section()).isEqualTo("주계약");
        assertThat(first.article()).isEqualTo("제5조");
        assertThat(first.articleTitle()).isEqualTo("보험금 지급에 관한 세부규정");
        assertThat(first.paragraph()).isEqualTo("제1항");
        assertThat(first.page()).isEqualTo(87);
        assertThat(first.text())
                .isEqualTo("암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.");

        Clause second = result.clauses().get(1);
        assertThat(second.clauseId()).isEqualTo("main-art5-p2");
        assertThat(second.paragraph()).isEqualTo("제2항");
    }

    @Test
    void 특약_제목이_나오면_이후_조항을_특약으로_분류한다() {
        String page = """
                제1조(목적) 이 특별약관은 주계약에 부가하여 적용합니다.

                암진단 특별약관

                제5조(보험금 지급에 관한 세부규정)
                ① 암보장개시일은 계약일부터 90일이 지난날의 다음날로 합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(1, page)));

        assertThat(result.riders()).hasSize(1);
        RiderRange rider = result.riders().get(0);
        assertThat(rider.riderId()).isEqualTo("rider01");
        assertThat(rider.name()).isEqualTo("암진단 특별약관");

        assertThat(result.clauses()).hasSize(2);
        Clause mainClause = result.clauses().get(0);
        assertThat(mainClause.riderId()).isNull();
        assertThat(mainClause.clauseId()).isEqualTo("main-art1");

        Clause riderClause = result.clauses().get(1);
        assertThat(riderClause.riderId()).isEqualTo("rider01");
        assertThat(riderClause.section()).isEqualTo("암진단 특별약관");
        assertThat(riderClause.clauseId()).isEqualTo("rider01-art5-p1");
    }

    @Test
    void 조를_하나도_인식하지_못하면_구조분석_예외를_던진다() {
        String page = """
                표지
                목차
                1. 보험 안내
                2. 유의 사항
                """;

        assertThatThrownBy(() -> analyzer.analyze(List.of(new PageText(1, page))))
                .isInstanceOf(StructureAnalysisException.class)
                .hasMessageContaining("조항 구조");
    }

    @Test
    void 같은_이름의_특약_제목이_빈_쪽에서_또_나오면_조항이_있는_쪽만_남는다() {
        // 표가 쪽을 넘어가는 등의 이유로 같은 특약 제목이 조항 없이 한 번 더
        // 인식되면(실제 495쪽 ABL 약관에서 재현됨), 빈 쪽의 특약은 결과에
        // 남지 않아야 한다.
        String page = """
                암진단 특별약관

                제5조(보험금 지급에 관한 세부규정)
                ① 암보장개시일은 계약일부터 90일이 지난날의 다음날로 합니다.

                암진단 특별약관

                제6조(보험금을 지급하지 않는 사유)
                ① 다음 각 호의 경우에는 보험금을 지급하지 않습니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(1, page)));

        assertThat(result.riders()).hasSize(1);
        RiderRange rider = result.riders().get(0);
        assertThat(rider.name()).isEqualTo("암진단 특별약관");

        // 두 번째로 인식된 특약 제목에 속한 조항도 첫 번째 특약(쪽수가 더 앞선
        // 쪽)의 riderId로 합쳐져 결과에서 빠지지 않는다.
        assertThat(result.clauses()).hasSize(2);
        assertThat(result.clauses()).allMatch(c -> rider.riderId().equals(c.riderId()));
    }

    @Test
    void 쪽을_넘나드는_항은_마지막_항이_있던_쪽_번호를_유지한다() {
        var pages = List.of(
                new PageText(10, "제3조(지급사유)\n① 다음 각 호의 경우 보험금을 지급합니다.\n1. 사망"),
                new PageText(11, "2. 장해\n② 지급 절차는 별도로 정합니다.")
        );

        var result = analyzer.analyze(pages);

        assertThat(result.clauses()).hasSize(2);
        assertThat(result.clauses().get(0).page()).isEqualTo(10);
        assertThat(result.clauses().get(0).text()).contains("1. 사망");
        assertThat(result.clauses().get(1).page()).isEqualTo(11);
    }
}
