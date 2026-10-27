package com.insurance.explainer.analysis;

import java.util.List;

/**
 * 6개 점검 항목과 중요도(명세서 6장 "중요도 기준" 표). AI가 매기지 않고
 * 백엔드가 점검 항목별로 미리 정한 값을 부여한다(F-18).
 */
public record CheckItem(int id, String name, String importance) {

    public static final List<CheckItem> ALL = List.of(
            new CheckItem(1, "보험금을 주지 않는 경우", "HIGH"),
            new CheckItem(2, "기다려야 하는 기간", "HIGH"),
            new CheckItem(3, "보험료가 오를 수 있음", "NORMAL"),
            new CheckItem(4, "알릴 의무", "HIGH"),
            new CheckItem(5, "약관이 정한 뜻", "NORMAL"),
            new CheckItem(6, "중간에 해지하면", "NORMAL")
    );

    public static CheckItem byId(int id) {
        return ALL.stream()
                .filter(c -> c.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 점검 항목: " + id));
    }
}
