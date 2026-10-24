package com.insurance.explainer.structure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 약관 형식의 함정(목차, 본문 중 조항 참조, 반복 머리글·바닥글, 서로 다른 항
 * 번호 표기)을 흉내 낸 쪽별 텍스트로 DocumentStructureAnalyzer를 검증하는
 * 확인용 테스트. 결과를 표준 출력으로도 보여준다(특약 목록, 조항 목록:
 * clause_id·쪽·앞 50자).
 * <p>
 * 실제 약관 PDF는 docs/samples/ 에 추가되면 {@link RealSampleTermsStructureAnalysisTest}가
 * 자동으로 같은 방식으로 돌려본다.
 */
class DocumentStructureAnalyzerSyntheticTermsTest {

    private final DocumentStructureAnalyzer analyzer = new DocumentStructureAnalyzer();

    @Test
    void 함정이_섞인_약관을_정확히_구조분석한다() {
        List<PageText> pages = SampleTermsLoader.load("/structure-samples/sample-01-synthetic-terms.txt");

        var result = analyzer.analyze(pages);

        printDiagnostics(result);

        // 특약: 목차에 나온 "주계약"(특약 아님)은 제외, 본문에 실제로 등장한
        // "암진단 특별약관", "입원특약" 둘만 남아야 한다. 목차의 중복 언급으로
        // rider 번호가 밀리지 않아야 한다.
        assertThat(result.riders()).extracting(RiderRange::riderId, RiderRange::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("rider01", "암진단 특별약관"),
                        org.assertj.core.groups.Tuple.tuple("rider02", "입원특약")
                );

        assertThat(result.clauses()).hasSize(6);

        // 목차의 "제1조(목적)" "제2조(...)" 는 본문이 없으므로 조항으로 남지 않고,
        // 쪽 3의 실제 본문만 main-art1 / main-art2-p1 로 인식되어야 한다.
        Clause mainArt1 = findClause(result, "main-art1");
        assertThat(mainArt1.page()).isEqualTo(3);
        assertThat(mainArt1.riderId()).isNull();
        assertThat(mainArt1.text()).isEqualTo("이 약관은 보험계약의 내용을 규정합니다.");

        // 본문 중간의 "제3조 제1항에 따라"는 새 조로 오인되지 않고 ①의 본문에
        // 그대로 이어붙어야 한다.
        Clause mainArt2P1 = findClause(result, "main-art2-p1");
        assertThat(mainArt2P1.text()).isEqualTo(
                "회사는 피보험자에게 보험사고가 발생한 경우 보험금을 지급합니다. 제3조 제1항에 따라 산정한 금액을 지급합니다.");

        // 암진단 특별약관: ①②(원문자) 형식, 조가 쪽 4→5 경계를 넘는다.
        Clause rider1P1 = findClause(result, "rider01-art5-p1");
        assertThat(rider1P1.riderId()).isEqualTo("rider01");
        assertThat(rider1P1.section()).isEqualTo("암진단 특별약관");
        assertThat(rider1P1.page()).isEqualTo(4);
        assertThat(rider1P1.text()).isEqualTo(
                "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.");

        Clause rider1P2 = findClause(result, "rider01-art5-p2");
        assertThat(rider1P2.page()).isEqualTo(5);
        assertThat(rider1P2.text()).isEqualTo(
                "다만 회사가 정한 사유에 해당하면 보장개시일을 달리 적용할 수 있습니다.");

        // 입원특약: "1. 2." 형식의 항 번호도 원문자와 똑같이 항으로 인식돼야 한다.
        Clause rider2P1 = findClause(result, "rider02-art8-p1");
        assertThat(rider2P1.riderId()).isEqualTo("rider02");
        assertThat(rider2P1.text()).isEqualTo("질병으로 4일 이상 계속 입원하였을 때");

        Clause rider2P2 = findClause(result, "rider02-art8-p2");
        assertThat(rider2P2.text()).isEqualTo("상해로 4일 이상 계속 입원하였을 때");

        // 쪽마다 반복되는 머리글("(무)○○암보험 2025년 4월 개정")·바닥글(안내 문구,
        // 쪽 번호)이 어느 조항 본문에도 섞여 들어가지 않아야 한다.
        assertThat(result.clauses()).allSatisfy(c -> {
            assertThat(c.text()).doesNotContain("(무)○○암보험");
            assertThat(c.text()).doesNotContain("약관 예시");
            assertThat(c.text()).doesNotMatch(".*- \\d+ -.*");
        });
    }

    private Clause findClause(DocumentStructureAnalyzer.StructureAnalysisResult result, String clauseId) {
        return result.clauses().stream()
                .filter(c -> c.clauseId().equals(clauseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("clauseId를 찾지 못함: " + clauseId));
    }

    private void printDiagnostics(DocumentStructureAnalyzer.StructureAnalysisResult result) {
        System.out.println("==== sample-01-synthetic-terms ====");
        System.out.println("특약 수: " + result.riders().size());
        for (RiderRange rider : result.riders()) {
            System.out.println("  - " + rider.riderId() + ": " + rider.name() + " (p." + rider.pageStart() + ")");
        }
        System.out.println("조항 수: " + result.clauses().size());
        for (Clause c : result.clauses()) {
            String preview = c.text().length() > 50 ? c.text().substring(0, 50) : c.text();
            System.out.printf("  - %-20s p.%-4d %s%n", c.clauseId(), c.page(), preview);
        }
    }
}
