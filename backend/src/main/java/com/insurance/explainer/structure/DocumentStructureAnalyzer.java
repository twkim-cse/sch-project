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
    private static final String TOC_MARKER = "목차";

    // "암진단 특별약관", "입원특약" 처럼 조 번호 없이 단독으로 나오는 짧은 제목 줄을
    // 특약 시작으로 본다.
    private static final Pattern RIDER_HEADING =
            Pattern.compile("^([가-힣A-Za-z0-9·()\\s]{2,30}(특별약관|특약))$");

    // 제5조, 제5조(보험금 지급에 관한 세부규정). "제3조 제1항에 따라" 처럼 조 번호 뒤에
    // 곧바로 다른 항을 참조하는 본문 중 문구는 조 제목이 아니므로 제외한다.
    private static final Pattern ARTICLE =
            Pattern.compile("^제(\\d+)조(?!\\s*제\\d+항)(\\s*\\(([^)]*)\\))?");

    // 원문자 ①~⑳ 로 시작하는 항
    private static final Pattern CIRCLED_PARAGRAPH =
            Pattern.compile("^([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳])");

    // "1. ", "2. " 처럼 숫자+마침표로 항을 표시하는 특약용. 같은 조 안에서 원문자 항을
    // 이미 썼다면(하위 목록 등과 구분하기 위해) 더는 새 항 표시로 보지 않는다.
    private static final Pattern NUMBERED_PARAGRAPH =
            Pattern.compile("^(\\d+)\\.\\s+(.*)$");

    // 쪽마다 반복되는 "- 3 -", "3", "3 / 9" 같은 쪽 번호만 있는 줄
    private static final Pattern PAGE_NUMBER_ONLY =
            Pattern.compile("^-?\\s*\\d{1,4}\\s*-?$|^\\d+\\s*/\\s*\\d+$");

    private static final String CIRCLED_DIGITS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";

    public StructureAnalysisResult analyze(List<PageText> pages) {
        List<Line> lines = flattenAndCleanLines(pages);

        List<Clause> clauses = new ArrayList<>();
        Map<String, RiderRange> riders = new LinkedHashMap<>();

        String currentRiderId = null; // null = 주계약
        String currentSection = MAIN_SECTION;
        int riderSeq = 0;

        String currentArticleNo = null;
        String currentArticleTitle = null;
        String currentParagraphNo = null; // null이면 "본문"(항 구분 없음)
        boolean circledUsedInArticle = false;
        int currentClausePage = 0;
        StringBuilder buffer = new StringBuilder();

        boolean inToc = false;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String text = line.text();

            if (!inToc && TOC_MARKER.equals(text)) {
                inToc = true;
                continue;
            }

            boolean isHeading = RIDER_HEADING.matcher(text).matches() || ARTICLE.matcher(text).find();

            if (inToc) {
                if (isHeading) {
                    Line next = i + 1 < lines.size() ? lines.get(i + 1) : null;
                    boolean nextIsHeading = next != null
                            && (RIDER_HEADING.matcher(next.text()).matches() || ARTICLE.matcher(next.text()).find());
                    if (nextIsHeading || next == null) {
                        // 목차 안의 또 다른 항목일 뿐이다. 상태를 바꾸지 않고 건너뛴다.
                        continue;
                    }
                    // 다음 줄이 실제 본문이면 목차가 끝난 것이다. 이 줄부터 평소처럼 처리한다.
                    inToc = false;
                } else {
                    // 목차의 설명 글·쪽 번호 등. 건너뛴다.
                    continue;
                }
            }

            Matcher riderMatcher = RIDER_HEADING.matcher(text);
            if (riderMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                riderSeq++;
                currentRiderId = "rider%02d".formatted(riderSeq);
                currentSection = text;
                currentArticleNo = null;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                riders.put(currentRiderId, new RiderRange(currentRiderId, text, line.page()));
                continue;
            }

            Matcher articleMatcher = ARTICLE.matcher(text);
            if (articleMatcher.find()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                currentArticleNo = "제" + articleMatcher.group(1) + "조";
                currentArticleTitle = articleMatcher.group(3);
                currentParagraphNo = null;
                circledUsedInArticle = false;
                currentClausePage = line.page();
                String rest = text.substring(articleMatcher.end()).strip();
                if (!rest.isEmpty()) {
                    buffer.append(rest).append(' ');
                }
                continue;
            }

            if (currentArticleNo == null) {
                // 아직 조를 인식하기 전(표지 등)은 무시한다.
                continue;
            }

            Matcher circledMatcher = CIRCLED_PARAGRAPH.matcher(text);
            if (circledMatcher.find()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                int idx = CIRCLED_DIGITS.indexOf(circledMatcher.group(1));
                currentParagraphNo = "제" + (idx + 1) + "항";
                circledUsedInArticle = true;
                currentClausePage = line.page();
                buffer.append(text.substring(1).strip()).append(' ');
                continue;
            }

            Matcher numberedMatcher = NUMBERED_PARAGRAPH.matcher(text);
            if (!circledUsedInArticle && numberedMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer));
                currentParagraphNo = "제" + numberedMatcher.group(1) + "항";
                currentClausePage = line.page();
                buffer.append(numberedMatcher.group(2)).append(' ');
                continue;
            }

            if (buffer.isEmpty()) {
                currentClausePage = line.page();
            }
            buffer.append(text).append(' ');
        }
        clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                currentArticleTitle, currentParagraphNo, currentClausePage, buffer));

        if (clauses.isEmpty()) {
            throw new StructureAnalysisException("조항 구조를 인식하지 못했습니다.");
        }

        List<RiderRange> usedRiders = riders.values().stream()
                .filter(r -> clauses.stream().anyMatch(c -> r.riderId().equals(c.riderId())))
                .toList();

        return new StructureAnalysisResult(clauses, usedRiders);
    }

    /**
     * 모든 쪽의 글자를 줄 단위로 펼치면서, 쪽 번호만 있는 줄과 모든(또는 대부분의) 쪽
     * 맨 앞에 똑같이 반복되는 머리글 줄을 걸러낸다. 제목/조 패턴과 일치하는 줄은
     * 머리글로 보지 않는다(실제 조항 제목이 반복 빈도만으로 걸러지지 않도록).
     */
    private List<Line> flattenAndCleanLines(List<PageText> pages) {
        List<List<String>> perPage = new ArrayList<>();
        for (PageText page : pages) {
            List<String> pageLines = new ArrayList<>();
            for (String raw : page.text().split("\n")) {
                String stripped = raw.strip();
                if (stripped.isEmpty() || PAGE_NUMBER_ONLY.matcher(stripped).matches()) {
                    continue;
                }
                pageLines.add(stripped);
            }
            perPage.add(pageLines);
        }

        String headerNoise = findRepeatedEdgeLine(perPage, true);
        String footerNoise = findRepeatedEdgeLine(perPage, false);

        List<Line> result = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            int pageNumber = pages.get(i).page();
            List<String> pageLines = perPage.get(i);
            for (String l : pageLines) {
                if (l.equals(headerNoise) || l.equals(footerNoise)) {
                    continue;
                }
                result.add(new Line(pageNumber, l));
            }
        }
        return result;
    }

    private String findRepeatedEdgeLine(List<List<String>> perPage, boolean fromStart) {
        if (perPage.size() < 2) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int pagesWithContent = 0;
        for (List<String> pageLines : perPage) {
            if (pageLines.isEmpty()) {
                continue;
            }
            pagesWithContent++;
            String edge = fromStart ? pageLines.get(0) : pageLines.get(pageLines.size() - 1);
            if (RIDER_HEADING.matcher(edge).matches() || ARTICLE.matcher(edge).find()) {
                continue; // 실제 제목일 수 있으니 머리글 후보로 보지 않는다.
            }
            counts.merge(edge, 1, Integer::sum);
        }
        if (pagesWithContent == 0) {
            return null;
        }
        int threshold = pagesWithContent; // 절반을 넘는 쪽에서 반복되면 노이즈로 본다.
        return counts.entrySet().stream()
                .filter(e -> e.getValue() * 2 > threshold)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
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

    private record Line(int page, String text) {
    }

    public record StructureAnalysisResult(List<Clause> clauses, List<RiderRange> riders) {
    }
}
