package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.CardDto;
import org.springframework.stereotype.Component;

// F-14 구현 전까지 쓰는 임시 구현: 모든 카드를 통과시킨다.
@Component
public class PassthroughNumberConsistencyChecker implements NumberConsistencyChecker {

    @Override
    public boolean isConsistent(CardDto card, String quote) {
        return true;
    }
}
