package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.structure.Clause;
import com.insurance.explainer.structure.ClauseTextNormalizer;
import org.springframework.stereotype.Component;

/**
 * F-10 인용 대조(명세서 6장).
 * quote와 해당 조항의 text를 같은 정리 규칙({@link ClauseTextNormalizer})으로 맞춘 뒤,
 * quote가 text 안에 그대로 들어 있는지 확인한다.
 */
@Component
public class QuoteValidatorImpl implements QuoteValidator {

    @Override
    public boolean isQuoteFoundInClause(ExtractionDto extraction, Clause sourceClause) {
        if (extraction.quote() == null || extraction.quote().isBlank()) {
            return false;
        }
        String normalizedQuote = ClauseTextNormalizer.normalize(extraction.quote());
        String normalizedText = ClauseTextNormalizer.normalize(sourceClause.text());
        return normalizedText.contains(normalizedQuote);
    }
}
