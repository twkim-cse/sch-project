package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.ExtractionDto;
import com.insurance.explainer.structure.Clause;

/**
 * F-10 인용 대조: quote가 해당 조항 원문에 실제로 있는지 글자 단위로 확인한다.
 * CHECKING_QUOTES 단계에서 쓴다.
 */
public interface QuoteValidator {
    boolean isQuoteFoundInClause(ExtractionDto extraction, Clause sourceClause);
}
