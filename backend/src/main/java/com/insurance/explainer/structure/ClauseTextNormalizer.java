package com.insurance.explainer.structure;

/**
 * 인용 대조 전 정리 규칙(명세서 6장).
 * 줄바꿈을 공백으로 바꾸고 연속된 공백을 하나로 줄인다. 글자 자체는 바꾸지 않는다.
 * 조항 데이터를 만들 때(F-04) 한 번 적용해 저장해 두고, AI에 보내는 text와
 * 원문 보기(GET /api/documents/{id}/clauses/{clauseId})에 내려주는 text에 같은 것을 쓴다.
 */
public final class ClauseTextNormalizer {

    private ClauseTextNormalizer() {
    }

    public static String normalize(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .replaceAll(" {2,}", " ")
                .trim();
    }
}
