package com.insurance.explainer.structure;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-04 문서 읽기·구조 분석.
 * <p>
 * 쪽 번호가 붙은 글자(F-02에서 추출된 {@link PageText})를 받아 주계약·특약 범위와
 * 관·조·항 번호를 인식해 조항({@link Clause}) 단위로 분할한다.
 * 조 번호를 하나도 인식하지 못하면 분석을 중단하고 {@link StructureAnalysisException}을 던진다.
 */
@Component
public class DocumentStructureAnalyzer {

    private static final String MAIN_SECTION = "주계약";

    // "암진단 특별약관", "입원특약" 처럼 조 번호 없이 단독으로 나오는 짧은 제목 줄을
    // 특약 시작으로 본다.
    private static final Pattern RIDER_HEADING =
            Pattern.compile("^([가-힣A-Za-z0-9·()\\s]{2,30}(특별약관|특약))$");

    // 제5조, 제5조(보험금 지급에 관한 세부규정)
    private static final Pattern ARTICLE =
            Pattern.compile("^제(\\d+)조(\\s*\\(([^)]*)\\))?");

    // 원문자 ①~⑳ 로 시작하는 항
    private static final Pattern CIRCLED_PARAGRAPH =
            Pattern.compile("^([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳])");

    private static final String CIRCLED_DIGITS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";

    public StructureAnalysisResult analyze(List<PageText> pages) {
        List<Clause> clauses = new ArrayList<>();
        Map<String, RiderRange> riders = new LinkedHashMap<>();

        String currentRiderId = null; // null = 주계약
        String currentSection = MAIN_SECTION;
        int riderSeq = 0;

        String currentArticleNo = null;
        String currentArticleTitle = null;
        String currentParagraphNo = null; // null이면 "본문"(항 구분 없음)
        int currentClausePage = 0;
        StringBuilder buffer = new StringBuilder();

        for (PageText page : pages) {
            for (String rawLine : page.text().split("\n")) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }

                Matcher riderMatcher = RIDER_HEADING.matcher(line);
                if (riderMatcher.matches()) {
                    clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                            currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                    riderSeq++;
                    currentRiderId = "rider%02d".formatted(riderSeq);
                    currentSection = line;
                    currentArticleNo = null;
                    currentArticleTitle = null;
                    currentParagraphNo = null;
                    riders.put(currentRiderId, new RiderRange(currentRiderId, line, page.page()));
                    continue;
                }

                Matcher articleMatcher = ARTICLE.matcher(line);
                if (articleMatcher.find()) {
                    clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                            currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                    currentArticleNo = "제" + articleMatcher.group(1) + "조";
                    currentArticleTitle = articleMatcher.group(3);
                    currentParagraphNo = null;
                    currentClausePage = page.page();
                    String rest = line.substring(articleMatcher.end()).strip();
                    if (!rest.isEmpty()) {
                        buffer.append(rest).append(' ');
                    }
                    continue;
                }

                if (currentArticleNo == null) {
                    // 아직 조를 인식하기 전(표지·목차 등)은 무시한다.
                    continue;
                }

                Matcher paragraphMatcher = CIRCLED_PARAGRAPH.matcher(line);
                if (paragraphMatcher.find()) {
                    clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                            currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                    int idx = CIRCLED_DIGITS.indexOf(paragraphMatcher.group(1));
                    currentParagraphNo = "제" + (idx + 1) + "항";
                    currentClausePage = page.page();
                    buffer.append(line.substring(1).strip()).append(' ');
                    continue;
                }

                if (buffer.isEmpty()) {
                    currentClausePage = page.page();
                }
                buffer.append(line).append(' ');
            }
        }
        clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                currentArticleTitle, currentParagraphNo, currentClausePage, buffer));

        if (clauses.isEmpty()) {
            throw new StructureAnalysisException("조항 구조를 인식하지 못했습니다.");
        }

        return new StructureAnalysisResult(clauses, List.copyOf(riders.values()));
    }

    private List<Clause> flush(String riderId, String section, String articleNo,
                                String articleTitle, String paragraphNo, int page,
                                StringBuilder buffer) {
        if (articleNo == null || buffer.isEmpty()) {
            buffer.setLength(0);
            return List.of();
        }
        String text = ClauseTextNormalizer.normalize(buffer.toString());
        buffer.setLength(0);
        if (text.isEmpty()) {
            return List.of();
        }

        String scope = riderId == null ? "main" : riderId;
        String articleNum = articleNo.replaceAll("\\D", "");
        String clauseId = scope + "-art" + articleNum
                + (paragraphNo == null ? "" : "-p" + paragraphNo.replaceAll("\\D", ""));

        return List.of(new Clause(clauseId, riderId, section, articleNo, articleTitle,
                paragraphNo, page, false, text));
    }

    public record StructureAnalysisResult(List<Clause> clauses, List<RiderRange> riders) {
    }
}
