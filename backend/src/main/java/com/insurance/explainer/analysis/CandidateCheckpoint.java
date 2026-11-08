package com.insurance.explainer.analysis;

import com.insurance.explainer.analysis.ai.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Backend proposal only: private in-memory evidence, NOT durable/resumable yet. */
public record CandidateCheckpoint(CandidatePlanRequest source, CandidatePlanResponse plan,
        Map<String,ExtractResponse> succeeded, String failedPartId, String state, JsonNode coverage) {
    public CandidateCheckpoint {succeeded=Map.copyOf(succeeded);}
    public Map<String,Object> summary(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("state",state);
        out.put("completedPartIds",new ArrayList<>(succeeded.keySet()));
        out.put("pendingPartIds",plan.candidatePlan().parts().stream().map(CandidatePartDto::partId).filter(id->!succeeded.containsKey(id)).toList());
        out.put("failedPartId",failedPartId);out.put("selectionId",plan.selection().selectionId());
        out.put("planId",plan.candidatePlan().planId());out.put("policyVersion",plan.selection().policyVersion());
        out.put("coverage",coverage);out.put("fullDocumentSuccess",false);
        out.put("resumable",false);out.put("durable",false);
        return out;
    }
}
