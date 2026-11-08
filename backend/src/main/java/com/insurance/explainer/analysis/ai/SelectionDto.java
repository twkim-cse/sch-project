package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// Local backend proposal: preserve selection identity, rule version and scope.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectionDto(String selectionId, String policyVersion, com.fasterxml.jackson.databind.JsonNode coverage) {
    public SelectionDto(String selectionId){this(selectionId,null,null);}
}
