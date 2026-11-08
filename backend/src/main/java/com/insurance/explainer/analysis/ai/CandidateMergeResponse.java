package com.insurance.explainer.analysis.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

// Local backend proposal: retain candidate scope and merge identities.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidateMergeResponse(List<ExtractionDto> extractions,
        com.fasterxml.jackson.databind.JsonNode coverage, String selectionId, String planId) {
    public CandidateMergeResponse(List<ExtractionDto> extractions){this(extractions,null,null,null);}
}
