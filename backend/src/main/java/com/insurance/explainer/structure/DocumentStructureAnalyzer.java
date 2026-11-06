package com.insurance.explainer.structure;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-04 문서 읽기·구조 분석.
 * <p>
 * 쪽 번호가 붙은 글자(F-02에서 추출된 {@link PageText})를 받아 절·관·보장 항목·특약
 * 계층과 조·항 번호를 인식해 조항({@link Clause}) 단위로 분할한다.
 * 조 번호를 하나도 인식하지 못하면 분석을 중단하고 {@link StructureAnalysisException}을 던진다.
 */
@Component
public class DocumentStructureAnalyzer {

    private static final String MAIN_SECTION = "주계약";
    private static final String TOC_MARKER = "목차";

    // "암진단 특별약관", "입원특약" 처럼 번호 없이 단독으로 나오는 특약 제목.
    // 접미사 앞부분을 따로 잡아서, "이 특약"/"그 특약"처럼 본문 중 짧은 지시어
    // 참조인 경우를 isRiderHeading에서 걸러낸다(길이만으로는 "입원특약"과
    // 구분할 수 없다).
    private static final Pattern NAMED_RIDER_HEADING =
            Pattern.compile("^([가-힣A-Za-z0-9·()\\s]{2,40})(특별약관|특약)$");

    // 제목이 아니라 본문에서 특정 특약을 가리키는 짧은 지시어. "이 특약" 등은
    // 실제 특약 이름이 아니므로 제외한다.
    private static final Set<String> RIDER_REFERENCE_WORDS =
            Set.of("이", "그", "본", "해당", "동", "위", "아래", "같은", "대상");

    // "1.  상해관련 특별약관", "1-1  상해입원일당(1-180일)(갱신형)보장 특별약관"
    // 처럼 번호(와 선택적 세부번호)로 시작하는 특약/보장 항목 제목. 로마 숫자(Ⅱ 등)나
    // 글꼴이 깨져 남는 문자가 제목 안에 섞여도 인식하도록 문자 종류를 넓게 허용한다.
    private static final Pattern NUMBERED_RIDER_HEADING =
            Pattern.compile("^\\d+(-\\d+)?\\.?\\s{1,3}\\S.{1,55}(특별약관|특약)$");

    // 제5조, 제5조(보험금 지급에 관한 세부규정). "제3조 제1항에 따라" 처럼 조 번호 뒤에
    // 곧바로 다른 항을 참조하는 본문 중 문구는 조 제목이 아니므로 제외한다.
    private static final Pattern ARTICLE =
            Pattern.compile("^제(\\d+)조(?!\\s*제\\d+항)(\\s*\\(([^)]*)\\))?");

    // 제1절 일반조항 / 제2절 보통약관의 보장
    private static final Pattern SECTION_HEADING =
            Pattern.compile("^제\\s*(\\d+)\\s*절\\s+(.+)$");

    // 제1관 목적 및 용어의 정의 / 제2관 보험금의 지급
    private static final Pattern GWAN_HEADING =
            Pattern.compile("^제\\s*(\\d+)\\s*관\\s+(.+)$");

    // 제1장 질병 관련 특별약관 / 제2장 제도성 특별약관
    private static final Pattern CHAPTER_HEADING =
            Pattern.compile("^제\\s*(\\d+)\\s*장\\s+(.+)$");

    // "1. 암진단비(유사암제외)【갱신계약】", "18-1. 항암약물치료비(연간1회한)" 처럼
    // 번호(+세부번호) + 제목으로 보장/특약 항목을 표시하는 줄. 본문 중 호("1. 피보
    // 험자가...")와 모양이 같으므로, 뒤이어 제1조가 나오는지 보고 구분한다
    // (isCoverageItemHeading).
    private static final Pattern COVERAGE_HEADING =
            Pattern.compile("^(\\d+(?:-\\d+)?)\\.\\s*(\\S.*)$");

    // 원문자 ①~⑳ 로 시작하는 항. 실제 PDF에서는 글꼴이 깨져 전부 비공개영역 문자
    // U+F000 한 글자로 추출되므로 그 문자도 항 시작으로 인식한다. 어느 쪽이든 조
    // 안에서 나온 순서대로 제1항, 제2항 ... 으로 번호를 매긴다.
    private static final Pattern CIRCLED_PARAGRAPH =
            Pattern.compile("^([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]|)");

    // "1. ", "2. " 처럼 숫자+마침표로 항을 표시하는 특약용. 같은 조 안에서 원문자 항을
    // 이미 썼다면(하위 목록 등과 구분하기 위해) 더는 새 항 표시로 보지 않는다.
    private static final Pattern NUMBERED_PARAGRAPH =
            Pattern.compile("^(\\d+)\\.\\s+(.*)$");

    // 쪽마다 반복되는 "- 3 -", "3", "3 / 9" 같은 쪽 번호만 있는 줄
    private static final Pattern PAGE_NUMBER_ONLY =
            Pattern.compile("^-?\\s*\\d{1,4}\\s*-?$|^\\d+\\s*/\\s*\\d+$");

    // 목차 항목 끝에 붙는 "····72" 같은 가운뎃점/마침표 리더 + 쪽 번호. 목차의 조
    // 항목과, 본문에 실제로 나오는 같은 번호의 조를 구분하는 기준으로 쓴다.
    private static final Pattern DOT_LEADER_PAGE_REF = Pattern.compile("[·.]{2,}\\s*\\d{1,4}\\s*$");

    // 목차 항목 안에서 리더가 쪽 번호 뒤가 아니라 줄 중간 어디에나 나올 수 있는
    // 경우("53 ········· 특정 신체부위·질병 보장제한부 인수 특약" 처럼 쪽 번호가
    // 앞에 오는 목차 형식)까지 포함해, 특약 제목 인식에서는 위치를 가리지 않고
    // 가운뎃점/마침표 리더가 3개 이상 이어지면 목차 항목으로 본다.
    private static final Pattern DOT_LEADER_ANYWHERE = Pattern.compile("[·.]{3,}");

    private static final String CIRCLED_DIGITS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";

    public StructureAnalysisResult analyze(List<PageText> pages) {
        List<Line> lines = flattenAndCleanLines(pages);

        List<Clause> clauses = new ArrayList<>();
        Map<String, RiderRange> riders = new LinkedHashMap<>();

        String currentRiderId = null; // null = 주계약
        String currentSection = MAIN_SECTION;
        int riderSeq = 0;

        String sectionTitle = null; // 제N절 ...
        String gwanTitle = null;    // 제N관 ...
        String chapterTitle = null; // 제N장 ... (특별약관 영역 안에서만 쓰인다)
        String itemTitle = null;    // 보장/특약 항목 제목 (번호 + 제목)
        int itemSeq = 0;            // 0이면 보장 항목 영역 밖. main 범위 clauseId 충돌을 막는다.
        int nextItemSeq = 0;
        boolean inRiderRegion = false; // "특별약관" 영역에 들어섰는지

        String currentArticleNo = null;
        int currentArticleNum = 0; // 조 안내문 안의 "제2조" 등 다른 조 번호 인용과 실제 다음 조를 구분한다.
        String currentArticleTitle = null;
        String currentParagraphNo = null; // null이면 "본문"(항 구분 없음)
        int circledSeqInArticle = 0; // 조 안에서 원문자/마커 항이 나온 순서
        boolean circledUsedInArticle = false;
        int currentClausePage = 0;
        StringBuilder buffer = new StringBuilder();

        boolean inToc = false;
        boolean skippingSidebar = false;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String text = line.text();

            if (!inToc && text.replaceAll("\\s+", "").equals(TOC_MARKER)) {
                inToc = true;
                continue;
            }

            if (inToc) {
                // 목차 항목은 "제1절 일반조항··········72", "제1조(목적) ·····72"
                // 처럼 절·관·조 항목도 전부 가운뎃점 리더 + 쪽 번호로 끝난다. 실제
                // 본문의 절·관 제목(제1절 일반조항, 제1관 목적 및 용어의 정의)은
                // 리더가 없으므로 그 자리에서 바로 목차를 끝내고 처리한다.
                boolean isRealSectionOrGwan = looksLikeHeadingLine(text)
                        && (SECTION_HEADING.matcher(text).matches() || GWAN_HEADING.matcher(text).matches())
                        && !DOT_LEADER_PAGE_REF.matcher(text).find();
                if (isRealSectionOrGwan) {
                    inToc = false;
                    // 이 줄부터는 평소처럼 처리한다. 아래로 흐른다.
                } else {
                    // 목차의 "제1조"는 본문처럼 계속 다른 목차 항목(절·관·조·특약·
                    // 보장 항목 또는 리더 줄)으로 이어지므로, 리더가 없는 "제1조"를
                    // 만나도 바로 다음 줄이 또 목차 항목 모양이면 아직 목차 안이라고
                    // 본다. 다음 줄이 실제 본문일 때만 목차가 끝난다.
                    Matcher firstArticleMatcher = ARTICLE.matcher(text);
                    boolean isFirstArticleCandidate = firstArticleMatcher.find()
                            && "1".equals(firstArticleMatcher.group(1))
                            && !DOT_LEADER_PAGE_REF.matcher(text).find();
                    if (isFirstArticleCandidate) {
                        Line next = i + 1 < lines.size() ? lines.get(i + 1) : null;
                        if (next != null && looksLikeTocChainLine(next.text())) {
                            continue;
                        }
                        inToc = false;
                        // 이 줄(실제 제1조)부터는 평소처럼 처리한다. 아래로 흐른다.
                    } else {
                        continue;
                    }
                }
            }

            if (isSidebarHeader(text)) {
                skippingSidebar = true;
                continue;
            }
            if (skippingSidebar) {
                if (isSidebarResumePoint(text, currentArticleNum, lines, i)) {
                    skippingSidebar = false;
                } else {
                    continue;
                }
            }

            Matcher sectionMatcher = SECTION_HEADING.matcher(text);
            if (!inRiderRegion && looksLikeHeadingLine(text) && sectionMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                sectionTitle = "제" + sectionMatcher.group(1) + "절 " + sectionMatcher.group(2).strip();
                gwanTitle = null;
                itemTitle = null;
                itemSeq = 0;
                currentSection = sectionTitle;
                currentArticleNo = null;
                currentArticleNum = 0;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;
                continue;
            }

            Matcher gwanMatcher = GWAN_HEADING.matcher(text);
            if (!inRiderRegion && looksLikeHeadingLine(text) && gwanMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                gwanTitle = "제" + gwanMatcher.group(1) + "관 " + gwanMatcher.group(2).strip();
                itemTitle = null;
                itemSeq = 0;
                currentSection = sectionTitle == null ? gwanTitle : sectionTitle + " / " + gwanTitle;
                currentArticleNo = null;
                currentArticleNum = 0;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;
                continue;
            }

            Matcher chapterMatcher = CHAPTER_HEADING.matcher(text);
            if (looksLikeHeadingLine(text) && chapterMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                inRiderRegion = true;
                currentRiderId = null;
                chapterTitle = "제" + chapterMatcher.group(1) + "장 " + chapterMatcher.group(2).strip();
                itemTitle = null;
                itemSeq = 0;
                currentSection = chapterTitle;
                currentArticleNo = null;
                currentArticleNum = 0;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;
                continue;
            }

            if (isRiderHeading(text)) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                inRiderRegion = true;
                riderSeq++;
                currentRiderId = "rider%02d".formatted(riderSeq);
                currentSection = text;
                itemTitle = null;
                itemSeq = 0;
                currentArticleNo = null;
                currentArticleNum = 0;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;
                riders.put(currentRiderId, new RiderRange(currentRiderId, text, line.page()));
                continue;
            }

            Matcher coverageMatcher = COVERAGE_HEADING.matcher(text);
            if (coverageMatcher.matches() && isCoverageItemHeading(lines, i)) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                itemTitle = text;
                currentArticleNo = null;
                currentArticleNum = 0;
                currentArticleTitle = null;
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;

                if (inRiderRegion) {
                    // 특별약관 영역의 번호 붙은 항목(위암 진단비 특약 등)은 각자
                    // 하나의 특약으로 취급해 clause_id가 겹치지 않게 한다.
                    riderSeq++;
                    currentRiderId = "rider%02d".formatted(riderSeq);
                    itemSeq = 0;
                    currentSection = chapterTitle == null ? itemTitle : chapterTitle + " / " + itemTitle;
                    riders.put(currentRiderId, new RiderRange(currentRiderId, itemTitle, line.page()));
                } else {
                    // 보통약관의 보장 항목: 조 번호가 항목마다 제1조부터 다시 시작되므로
                    // main 범위 clauseId에 항목 구간 번호를 섞어 넣는다.
                    itemSeq = ++nextItemSeq;
                    currentSection = sectionTitle == null ? itemTitle : sectionTitle + " / " + itemTitle;
                }
                continue;
            }

            Matcher articleMatcher = ARTICLE.matcher(text);
            if (articleMatcher.find()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                currentArticleNo = "제" + articleMatcher.group(1) + "조";
                currentArticleNum = Integer.parseInt(articleMatcher.group(1));
                currentArticleTitle = articleMatcher.group(3);
                currentParagraphNo = null;
                circledUsedInArticle = false;
                circledSeqInArticle = 0;
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
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                circledSeqInArticle++;
                currentParagraphNo = "제" + circledSeqInArticle + "항";
                circledUsedInArticle = true;
                currentClausePage = line.page();
                buffer.append(sanitizeStrayMarker(text.substring(1).strip(), circledSeqInArticle)).append(' ');
                continue;
            }

            Matcher numberedMatcher = NUMBERED_PARAGRAPH.matcher(text);
            if (!circledUsedInArticle && numberedMatcher.matches()) {
                clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                        currentArticleTitle, currentParagraphNo, currentClausePage, buffer,
                        circledSeqInArticle, itemSeq));
                currentParagraphNo = "제" + numberedMatcher.group(1) + "항";
                currentClausePage = line.page();
                buffer.append(sanitizeStrayMarker(numberedMatcher.group(2), circledSeqInArticle)).append(' ');
                continue;
            }

            if (buffer.isEmpty()) {
                currentClausePage = line.page();
            }
            buffer.append(sanitizeStrayMarker(text, circledSeqInArticle)).append(' ');
        }
        clauses.addAll(flush(currentRiderId, currentSection, currentArticleNo,
                currentArticleTitle, currentParagraphNo, currentClausePage, buffer, circledSeqInArticle,
                itemSeq));

        if (clauses.isEmpty()) {
            throw new StructureAnalysisException("조항 구조를 인식하지 못했습니다.");
        }

        List<Clause> dedupedClauses = deduplicateClauseIds(clauses);

        List<RiderRange> usedRiders = riders.values().stream()
                .filter(r -> dedupedClauses.stream().anyMatch(c -> r.riderId().equals(c.riderId())))
                .toList();

        return new StructureAnalysisResult(dedupedClauses, usedRiders);
    }

    /**
     * 인용된 외부 법령·전환대상계약 조문처럼 본문과 똑같은 모양("제2조", "제3조")의
     * 조 번호를 자체적으로 가진 글이 섞여 있으면, 현재 조보다 번호가 크다는 이유로
     * 새 조로 오인되어 clause_id가 겹칠 수 있다(제1절 일반조항·일부 특별약관에서
     * 실제로 발생). AI 서버는 clause_id가 요청 안에서 유일해야 한다고 요구하므로,
     * 그런 오인으로 생긴 겹침은 뒤에 나온 조항에 구분 접미사를 붙여 유일하게 만든다.
     */
    private List<Clause> deduplicateClauseIds(List<Clause> clauses) {
        Map<String, Integer> seenCounts = new LinkedHashMap<>();
        List<Clause> result = new ArrayList<>(clauses.size());
        for (Clause c : clauses) {
            int occurrence = seenCounts.merge(c.clauseId(), 1, Integer::sum);
            if (occurrence == 1) {
                result.add(c);
            } else {
                String dedupedId = c.clauseId() + "-dup" + occurrence;
                result.add(new Clause(dedupedId, c.riderId(), c.section(), c.article(),
                        c.articleTitle(), c.paragraph(), c.page(), c.fromTable(), c.text()));
            }
        }
        return result;
    }

    /**
     * 목차 안에서 다음 줄도 목차 항목처럼 보이는지 판단한다(조·절·관·특약 제목,
     * 번호 붙은 보장/특약 항목, 가운뎃점 리더 + 쪽 번호로 끝나는 줄). 목차는 이런
     * 줄들이 쭉 이어지다가 실제 본문을 만나면 끝난다.
     */
    /**
     * 번호 없는 특약 제목("암진단 특별약관")과 번호(세부번호) 붙은 특약/보장 항목
     * 제목("1.  상해관련 특별약관", "2-3  유사암진단(...)보장 특별약관")을 모두
     * 특약 시작으로 본다.
     */
    private boolean isRiderHeading(String text) {
        if (DOT_LEADER_ANYWHERE.matcher(text).find()) {
            // 목차의 "N  상해관련 특별약관 ·········72", "53 ········· 특정
            // 신체부위·질병 보장제한부 인수 특약" 같은 가운뎃점 리더가 든 줄은
            // 조 제목과 마찬가지로 목차 항목일 뿐, 실제 특약 제목이 아니다.
            return false;
        }
        Matcher namedMatcher = NAMED_RIDER_HEADING.matcher(text);
        if (namedMatcher.matches() && !RIDER_REFERENCE_WORDS.contains(namedMatcher.group(1).strip())) {
            return true;
        }
        return NUMBERED_RIDER_HEADING.matcher(text).matches();
    }

    private boolean looksLikeTocChainLine(String text) {
        return ARTICLE.matcher(text).find()
                || isRiderHeading(text)
                || COVERAGE_HEADING.matcher(text).matches()
                || DOT_LEADER_PAGE_REF.matcher(text).find()
                || (looksLikeHeadingLine(text) && (SECTION_HEADING.matcher(text).matches()
                        || GWAN_HEADING.matcher(text).matches()
                        || CHAPTER_HEADING.matcher(text).matches()));
    }

    /**
     * 사이드바(유의사항·예시·용어풀이·관련법규)를 건너뛰다가 본문으로 되돌아갈
     * 지점을 찾는다. 사이드바로 인용되는 법령 조문도 "제2조", "제3조" 같은 자기
     * 번호를 가지므로, 단순 ARTICLE 패턴 일치만으로는 복귀 지점을 알 수 없다.
     * 지금 조보다 번호가 큰 조("제N+1조" 이상)만 실제 복귀로 본다. 절·관·특약·
     * 보장 항목 제목이나, 원래 조로 돌아오는 항 마커도 복귀 지점이다.
     */
    private boolean isSidebarResumePoint(String text, int currentArticleNum, List<Line> lines, int i) {
        if (isRiderHeading(text)) {
            return true;
        }
        if (looksLikeHeadingLine(text) && (SECTION_HEADING.matcher(text).matches()
                || GWAN_HEADING.matcher(text).matches() || CHAPTER_HEADING.matcher(text).matches())) {
            return true;
        }
        if (CIRCLED_PARAGRAPH.matcher(text).find()) {
            return true;
        }
        if (COVERAGE_HEADING.matcher(text).matches() && isCoverageItemHeading(lines, i)) {
            return true;
        }
        Matcher articleMatcher = ARTICLE.matcher(text);
        if (articleMatcher.find()) {
            return Integer.parseInt(articleMatcher.group(1)) > currentArticleNum;
        }
        return false;
    }

    /**
     * "제3조 제1항에 따라" 처럼 본문 중간에 절·관·장 번호가 다른 문장에 섞여 나오는
     * 경우를 배제하기 위한 공통 조건. 실제 절·관·장 제목은 짧고 서술형 문장으로
     * 끝나지 않는다.
     */
    private boolean looksLikeHeadingLine(String text) {
        return text.length() <= 40 && !endsLikeSentence(text);
    }

    private boolean endsLikeSentence(String text) {
        return text.endsWith("다.") || text.endsWith("요.") || text.endsWith("다") || text.endsWith("습니다.");
    }

    /**
     * "1. 암진단비(유사암제외)【갱신계약】" 같은 보장/특약 항목 제목과, 조 본문 안의
     * 호("1. 피보험자가 고의로...")를 구분한다. 항목 제목 뒤에는 (안내 괄호 줄을 거쳐)
     * 반드시 제1조가 나온다는 점을 이용한다.
     */
    private boolean isCoverageItemHeading(List<Line> lines, int i) {
        for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
            String t = lines.get(j).text();
            Matcher articleMatcher = ARTICLE.matcher(t);
            if (articleMatcher.find()) {
                return "1".equals(articleMatcher.group(1));
            }
            if (isRiderHeading(t)
                    || COVERAGE_HEADING.matcher(t).matches()
                    || (looksLikeHeadingLine(t) && (SECTION_HEADING.matcher(t).matches()
                            || GWAN_HEADING.matcher(t).matches() || CHAPTER_HEADING.matcher(t).matches()))) {
                // 분명히 다른 구조 표지가 먼저 나왔으니 이 줄은 보장/특약 항목
                // 제목이 아니다.
                return false;
            }
            // 안내 괄호 줄, 제목이 줄바꿈되어 이어지는 줄("암진단비Ⅱ【갱신계약】
            // (【갱신계약】은...)")은 계속 다음 줄을 본다.
        }
        return false;
    }

    // "유 의 사 항", "예 시", "용 어 풀 이", "관 련 법 규" 처럼 글자 사이에 공백이
    // 섞여 들어가는 사이드바 머리글. 다음 구조 마커(조·항·절·관·장·특약 제목)가 나올
    // 때까지의 내용을 본문에서 제외한다.
    private boolean isSidebarHeader(String text) {
        String normalized = text.replaceAll("\\s+", "");
        return normalized.startsWith("유의사항")
                || normalized.startsWith("예시")
                || normalized.startsWith("용어풀이")
                || normalized.startsWith("관련법규");
    }

    /**
     * 글꼴 깨짐으로 남은 비공개영역 문자(U+F000)가 항 마커 처리 이후에도 줄 안에
     * 남아 있으면, 그 조에서 지금까지 나온 항 번호에 해당하는 원문자로 바꾼다. 아직
     * 항이 시작되지 않았다면(순서 0) 그냥 제거한다.
     */
    private String sanitizeStrayMarker(String s, int circledSeqInArticle) {
        if (s.indexOf('') < 0) {
            return s;
        }
        String replacement = circledSeqInArticle >= 1 && circledSeqInArticle <= CIRCLED_DIGITS.length()
                ? String.valueOf(CIRCLED_DIGITS.charAt(circledSeqInArticle - 1))
                : "";
        return s.replace("", replacement);
    }

    // Some PDFs render the space glyph in styled title lines (e.g. rider
    // headings) as U+0000 instead of a normal space. Replace it with a real
    // space and collapse repeats.
    private String normalizeNulAsSpace(String s) {
        if (s.indexOf((char) 0) < 0) {
            return s;
        }
        return s.replace((char) 0, (char) 32).replaceAll(" {2,}", " ").strip();
    }

    /**
     * 모든 쪽의 글자를 줄 단위로 펼치면서, 쪽 번호만 있는 줄, 세로 사이드바 머리글
     * (쪽 가장자리에 한 글자씩 세로로 찍히는 "공/통/사/항/..." 같은 탭 라벨)과, 모든
     * (또는 대부분의) 쪽 맨 앞에 똑같이 반복되는 머리글 줄을 걸러낸다. 제목/조 패턴과
     * 일치하는 줄은 머리글로 보지 않는다(실제 조항 제목이 반복 빈도만으로 걸러지지
     * 않도록).
     */
    private List<Line> flattenAndCleanLines(List<PageText> pages) {
        List<List<String>> perPage = new ArrayList<>();
        for (PageText page : pages) {
            List<String> pageLines = new ArrayList<>();
            for (String raw : page.text().split("\n")) {
                String stripped = normalizeNulAsSpace(raw.strip());
                if (stripped.isEmpty() || PAGE_NUMBER_ONLY.matcher(stripped).matches()) {
                    continue;
                }
                pageLines.add(stripped);
            }
            perPage.add(stripVerticalSidebar(pageLines));
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

    /**
     * 쪽 맨 앞에 한 글자씩 세로로 찍히는 탭 라벨("공","통","사","항", ...)을 걷어낸다.
     * 실제 조항 본문에서는 이렇게 짧게(길이 1) 연속으로 나오는 줄이 없으므로, 3줄
     * 이상 이어지는 한 글자짜리 줄 묶음만 제거한다.
     */
    private List<String> stripVerticalSidebar(List<String> pageLines) {
        int idx = 0;
        while (idx < pageLines.size() && pageLines.get(idx).codePointCount(0, pageLines.get(idx).length()) == 1) {
            idx++;
        }
        if (idx < 3) {
            return pageLines;
        }
        return pageLines.subList(idx, pageLines.size());
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
            if (isRiderHeading(edge) || ARTICLE.matcher(edge).find()) {
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
                                StringBuilder buffer, int circledSeqInArticle, int itemSeq) {
        if (articleNo == null || buffer.isEmpty()) {
            buffer.setLength(0);
            return List.of();
        }
        String text = ClauseTextNormalizer.normalize(buffer.toString());
        buffer.setLength(0);
        if (text.isEmpty()) {
            return List.of();
        }

        // 보통약관의 보장 항목은 조 번호가 항목마다 제1조부터 다시 시작되므로, main
        // 범위 clauseId에 항목 구간 번호를 섞어 겹치지 않게 한다(특약은 riderId 자체가
        // 구간이므로 별도 번호가 필요 없다).
        String scope = riderId == null
                ? (itemSeq > 0 ? "main-item" + itemSeq : "main")
                : riderId;
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
