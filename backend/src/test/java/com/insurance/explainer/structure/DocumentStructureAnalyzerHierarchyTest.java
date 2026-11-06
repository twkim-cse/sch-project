package com.insurance.explainer.structure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 KB 암보험 약관 PDF 조사 결과를 바탕으로 고친 부분(절·관·보장 항목 계층,
 * U+F000 항 마커, 호 처리, 머리글·바닥글·사이드바 제거, 목차 가운뎃점 리더 인식)을
 * 각각 확인하는 테스트.
 */
class DocumentStructureAnalyzerHierarchyTest {

    private final DocumentStructureAnalyzer analyzer = new DocumentStructureAnalyzer();

    @Test
    void 절과_관_경로를_section에_채운다() {
        String page = """
                제1절 일반조항
                제1관 목적 및 용어의 정의
                제1조(목적)
                이 약관의 목적을 정합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(72, page)));

        assertThat(result.clauses()).hasSize(1);
        Clause clause = result.clauses().get(0);
        assertThat(clause.clauseId()).isEqualTo("main-art1");
        assertThat(clause.section()).isEqualTo("제1절 일반조항 / 제1관 목적 및 용어의 정의");
        assertThat(clause.text()).isEqualTo("이 약관의 목적을 정합니다.");
    }

    @Test
    void 목차의_가운뎃점_리더_항목은_건너뛰고_실제_본문의_절_관_조만_인식한다() {
        String tocPage = """
                목   차
                제1절 일반조항··········································72
                제1관 목적 및 용어의 정의·································72
                제1조(목적) ···········································72
                """;
        String bodyPage = """
                제1절 일반조항
                제1관 목적 및 용어의 정의
                제1조(목적)
                이 약관의 목적을 정합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(3, tocPage), new PageText(72, bodyPage)));

        assertThat(result.clauses()).hasSize(1);
        Clause clause = result.clauses().get(0);
        assertThat(clause.page()).isEqualTo(72);
        assertThat(clause.section()).isEqualTo("제1절 일반조항 / 제1관 목적 및 용어의 정의");
        assertThat(clause.text()).isEqualTo("이 약관의 목적을 정합니다.");
    }

    @Test
    void 보통약관의_보장_항목마다_조_번호가_다시_시작돼도_clause_id가_겹치지_않는다() {
        String page = """
                제2절 보통약관의 보장
                1. 암진단비【갱신계약】
                제1조(보험금의 지급사유)
                암 진단비를 지급합니다.
                2. 유사암진단비【갱신계약】
                제1조(보험금의 지급사유)
                유사암 진단비를 지급합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(101, page)));

        assertThat(result.clauses()).hasSize(2);
        assertThat(result.clauses()).extracting(Clause::clauseId)
                .containsExactly("main-item1-art1", "main-item2-art1");
        assertThat(result.clauses().get(0).section())
                .isEqualTo("제2절 보통약관의 보장 / 1. 암진단비【갱신계약】");
        assertThat(result.clauses().get(1).section())
                .isEqualTo("제2절 보통약관의 보장 / 2. 유사암진단비【갱신계약】");
        // 보장 항목은 특약이 아니므로 리더 목록에는 들어가지 않는다.
        assertThat(result.riders()).isEmpty();
    }

    @Test
    void 특별약관_영역의_번호붙은_항목은_각각_별도_특약이_된다() {
        String page = """
                제1장 질병 관련 특별약관
                1. 위암 및 식도암 진단비【갱신계약】
                제1조(보험금의 지급사유)
                위암 진단비를 지급합니다.
                18-1. 항암약물치료비(연간1회한)
                제1조(보험금의 지급사유)
                항암약물치료비를 지급합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(116, page)));

        assertThat(result.riders()).extracting(RiderRange::name)
                .containsExactly("1. 위암 및 식도암 진단비【갱신계약】", "18-1. 항암약물치료비(연간1회한)");
        assertThat(result.clauses()).extracting(Clause::clauseId)
                .containsExactly("rider01-art1", "rider02-art1");
    }

    @Test
    void U_F000_마커는_나온_순서대로_항_번호가_되고_text에_남지_않는다() {
        String page = "제5조(보험금 지급에 관한 세부규정)\n"
                + " 암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.\n"
                + " 회사는 보험수익자에게 보험금을 지급합니다.\n";

        var result = analyzer.analyze(List.of(new PageText(75, page)));

        assertThat(result.clauses()).hasSize(2);
        Clause first = result.clauses().get(0);
        assertThat(first.clauseId()).isEqualTo("main-art5-p1");
        assertThat(first.paragraph()).isEqualTo("제1항");
        // 줄 중간에 남은 또 다른 U+F000도 지금까지의 항 번호(①)로 바뀌어야 한다.
        assertThat(first.text()).isEqualTo("암보장개시일은 ①계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.");
        assertThat(first.text()).doesNotContain("");

        Clause second = result.clauses().get(1);
        assertThat(second.clauseId()).isEqualTo("main-art5-p2");
        assertThat(second.paragraph()).isEqualTo("제2항");
        assertThat(second.text()).isEqualTo("회사는 보험수익자에게 보험금을 지급합니다.");
    }

    @Test
    void 호_목록은_보장_항목_제목으로_오인되지_않는다() {
        String page = """
                제6조(보험금을 지급하지 않는 사유)
                다음 중 어느 한가지로 보험금 지급사유가 발생한 때에는 보험금을 지급하지 않습니다.
                1. 피보험자가 고의로 자신을 해친 경우.
                2. 보험수익자가 고의로 피보험자를 해친 경우.
                """;

        var result = analyzer.analyze(List.of(new PageText(75, page)));

        assertThat(result.riders()).isEmpty();
        assertThat(result.clauses()).extracting(Clause::clauseId)
                .containsExactly("main-art6", "main-art6-p1", "main-art6-p2");
        assertThat(result.clauses().get(1).text()).isEqualTo("피보험자가 고의로 자신을 해친 경우.");
        assertThat(result.clauses().get(2).text()).isEqualTo("보험수익자가 고의로 피보험자를 해친 경우.");
    }

    @Test
    void 세로_사이드바_탭_라벨과_유의사항_사이드바를_본문에서_제외한다() {
        String page = "공\n통\n사\n항\n"
                + "-75-\n"
                + "제1조(목적)\n"
                + "이 약관의 목적을 정합니다.\n"
                + "유 의 사 항  \n"
                + "※ 참고용 안내 문구입니다.\n"
                + "제2조(정의)\n"
                + "용어를 정의합니다.\n";

        var result = analyzer.analyze(List.of(new PageText(75, page)));

        assertThat(result.clauses()).hasSize(2);
        Clause art1 = result.clauses().get(0);
        assertThat(art1.text()).isEqualTo("이 약관의 목적을 정합니다.");
        assertThat(art1.text()).doesNotContain("공", "통", "사", "항");

        Clause art2 = result.clauses().get(1);
        assertThat(art2.text()).isEqualTo("용어를 정의합니다.");
        assertThat(art2.text()).doesNotContain("유 의 사 항", "참고용 안내 문구");
    }

    @Test
    void 인용된_법령이_지금_조와_같은_번호를_써도_본문에_새로_끼어들지_않는다() {
        // "관 련 법 규" 사이드바 안에서 인용되는 법령 자체의 "제2조"가, 지금 이미
        // 안에 있는 본문 제2조와 같은 번호라고 해서 그 조를 다시 시작한 것으로
        // 오인되면 안 된다(지금 조보다 큰 번호만 복귀 지점으로 본다).
        String page = """
                제1조(목적)
                이 약관의 목적을 정합니다.
                제2조(정의)
                용어를 정의합니다.
                관 련 법 규
                관공서의 공휴일에 관한 규정 제2조
                제2조(공휴일)
                관공서의 공휴일은 다음과 같다.
                """;

        var result = analyzer.analyze(List.of(new PageText(74, page)));

        assertThat(result.clauses()).extracting(Clause::clauseId)
                .containsExactly("main-art1", "main-art2");
        Clause art2 = result.clauses().get(1);
        assertThat(art2.text()).isEqualTo("용어를 정의합니다.");
        assertThat(art2.text()).doesNotContain("공휴일");
    }

    @Test
    void 번호로_시작하는_특약_제목도_특약으로_인식한다() {
        // 다른 보험사 약관은 "제N장" 대신 "N.", "N-M" 번호로 절·특약 제목을 쓴다.
        String page = """
                1.  상해관련 특별약관
                1-1  상해입원일당(1-180일)(갱신형)보장 특별약관
                제1조 (보험금의 지급사유)
                지급합니다.
                1-2  상해수술입원일당(1-10일)(갱신형)보장 특별약관
                제1조 (보험금의 지급사유)
                수술입원일당을 지급합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(58, page)));

        assertThat(result.riders()).extracting(RiderRange::name)
                .containsExactly("1-1  상해입원일당(1-180일)(갱신형)보장 특별약관",
                        "1-2  상해수술입원일당(1-10일)(갱신형)보장 특별약관");
        // "1.  상해관련 특별약관" 자체도 번호+특별약관 모양이라 특약 하나(rider01)로
        // 세어지지만, 조문이 안 붙어서 결과 리더 목록에는 남지 않는다(번호가 1부터
        // 다시 시작하지 않고 건너뛴다).
        assertThat(result.clauses()).extracting(Clause::clauseId)
                .containsExactly("rider02-art1", "rider03-art1");
    }

    @Test
    void 본문_중_짧은_지시어_참조는_새_특약으로_오인되지_않는다() {
        // "이 특약", "그 특약" 처럼 본문에서 특정 특약을 가리키는 짧은 말은 제목이
        // 아니라 조 본문의 일부로 남아야 한다("입원특약" 같은 실제 짧은 이름은 여전히
        // 특약으로 인식돼야 한다).
        String page = """
                입원특약
                제1조 (보험금의 지급사유)
                이 특약
                에서 정한 사유로 입원하면 지급합니다.
                """;

        var result = analyzer.analyze(List.of(new PageText(58, page)));

        assertThat(result.riders()).extracting(RiderRange::name).containsExactly("입원특약");
        assertThat(result.clauses()).hasSize(1);
        assertThat(result.clauses().get(0).text()).isEqualTo("이 특약 에서 정한 사유로 입원하면 지급합니다.");
    }

    @Test
    void 특약_제목_안의_U_0000_문자는_공백으로_정리된다() {
        // 일부 PDF는 제목 줄의 공백 글리프가 U+0000으로 깨져 나온다. 상위 범주
        // 제목("3.  상해 및 질병관련 특별약관")은 번호만 있고 자기 조문이 없어,
        // 바로 다음에 나오는 실제 하위 특약 제목("3-1 ...")이 조문을 가져가야 한다.
        String categoryTitleWithNulChars = "3." + ' ' + "  상해" + ' ' + "및" + ' ' + "질병관련 특별약관";
        String page = categoryTitleWithNulChars + "\n"
                + "3-1  응급실내원진료비(응급)(갱신형)보장 특별약관\n"
                + "제1조 (보험금의 지급사유)\n"
                + "지급합니다.\n";

        var result = analyzer.analyze(List.of(new PageText(107, page)));

        assertThat(result.riders()).extracting(RiderRange::name)
                .containsExactly("3-1  응급실내원진료비(응급)(갱신형)보장 특별약관");
        assertThat(result.clauses()).hasSize(1);
        Clause clause = result.clauses().get(0);
        assertThat(clause.clauseId()).isEqualTo("rider02-art1");
        assertThat(clause.text()).doesNotContain(" ");
    }
}
