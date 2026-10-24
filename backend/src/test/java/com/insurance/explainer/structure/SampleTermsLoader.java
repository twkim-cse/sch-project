package com.insurance.explainer.structure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용: {@code ===PAGE n===} 구분자로 쪽을 나눈 평문 리소스를
 * {@link PageText} 목록으로 읽는다. 실제 PDF 대신 "실제 약관 형식을 흉내 낸"
 * 쪽별 텍스트 배열을 다루기 위한 확인용 로더다.
 */
final class SampleTermsLoader {

    private static final Pattern PAGE_MARKER = Pattern.compile("^===PAGE (\\d+)===$");

    private SampleTermsLoader() {
    }

    static List<PageText> load(String classpathResource) {
        try (InputStream in = SampleTermsLoader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("리소스를 찾을 수 없습니다: " + classpathResource);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<PageText> pages = new ArrayList<>();
            int currentPage = -1;
            StringBuilder buffer = new StringBuilder();

            for (String line : content.split("\n", -1)) {
                String stripped = line.stripTrailing();
                Matcher m = PAGE_MARKER.matcher(stripped.strip());
                if (m.matches()) {
                    if (currentPage != -1) {
                        pages.add(new PageText(currentPage, buffer.toString()));
                    }
                    currentPage = Integer.parseInt(m.group(1));
                    buffer = new StringBuilder();
                } else if (currentPage != -1) {
                    buffer.append(line).append('\n');
                }
            }
            if (currentPage != -1) {
                pages.add(new PageText(currentPage, buffer.toString()));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
