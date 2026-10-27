package com.insurance.explainer.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-14 숫자 일치 검사(명세서 6장).
 * title과 explanation에서 숫자+단위(일, 개월, 년, %, 회, 세, 원)를 뽑아,
 * 모두 quote에 있는지 확인한다. 하나라도 없으면 그 숫자를 unmatchedNumbers에 담는다.
 */
@Component
public class NumberConsistencyCheckerImpl implements NumberConsistencyChecker {

    // 쉼표가 섞인 큰 수(1,000원)도 인식하되, 글자 자체는 바꾸지 않고 그대로 비교한다.
    private static final Pattern NUMBER_WITH_UNIT =
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:일|개월|년|%|회|세|원)");

    @Override
    public NumberCheckResult check(String title, String explanation, String quote) {
        String combined = (title == null ? "" : title) + " " + (explanation == null ? "" : explanation);
        List<String> numbers = extractNumbers(combined);
        String normalizedQuote = quote == null ? "" : quote;

        List<String> unmatched = new ArrayList<>();
        for (String number : numbers) {
            if (!normalizedQuote.contains(number)) {
                unmatched.add(number);
            }
        }
        return unmatched.isEmpty() ? NumberCheckResult.ok() : new NumberCheckResult(false, unmatched);
    }

    private List<String> extractNumbers(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>(); // 중복 없이, 순서는 유지
        Matcher matcher = NUMBER_WITH_UNIT.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return List.copyOf(found);
    }
}
