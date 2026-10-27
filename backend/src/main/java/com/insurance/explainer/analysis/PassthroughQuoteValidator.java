package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.structure.Clause;
import org.springframework.stereotype.Component;

// F-10 구현 전까지 쓰는 임시 구현: 모든 인용을 통과시킨다.
@Component
public class PassthroughQuoteValidator implements QuoteValidator {

    @Override
    public boolean isQuoteFoundInClause(ExtractionDto extraction, Clause sourceClause) {
        return true;
    }
}
