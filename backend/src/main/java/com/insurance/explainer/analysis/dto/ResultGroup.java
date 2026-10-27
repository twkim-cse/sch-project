package com.insurance.explainer.analysis.dto;

import java.util.List;

public record ResultGroup(String scope, String riderId, String name, List<ResultCard> cards) {
}
