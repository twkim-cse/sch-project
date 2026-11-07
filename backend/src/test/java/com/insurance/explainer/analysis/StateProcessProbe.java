package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.explainer.analysis.ai.*;
import com.insurance.explainer.document.*;
import com.insurance.explainer.persistence.*;
import com.insurance.explainer.structure.Clause;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate-JVM disk probe with fabricated upstream only. No real policy/key/API. */
public final class StateProcessProbe {
    public static void main(String[] args) throws Exception {
        String root=args[1];
        try(var lease=new LocalStateLease(root)) {
            ObjectMapper mapper=new ObjectMapper();
            var clauses=List.of(new ClauseDto("one",null,"synthetic","one",null,null,1,false,"Synthetic fixture."),
                    new ClauseDto("two",null,"synthetic","two",null,null,1,false,"Synthetic fixture."));
            ExtractRequest source=new ExtractRequest("probe",List.of(1),clauses);
            AtomicInteger calls=new AtomicInteger();
            boolean write=args[0].equals("write");
            WebClient client=WebClient.builder().exchangeFunction(request->{
                if(!write)throw new AssertionError("Restart read probe must not dispatch HTTP");
                var response=mapper.createObjectNode();HttpStatus status=HttpStatus.OK;
                String path=request.url().getPath();
                if(path.equals("/health")) {response.put("model","offline-model");response.put("ai_configured",true);}
                else if(path.endsWith("/plan")) {
                    response.put("plan_id","probe-plan");response.put("preservation_verified",true);
                    var parts=response.putArray("parts");
                    for(var c:clauses) {var part=parts.addObject();part.put("part_id",c.clauseId());part.put("input_bytes",10_000);
                        part.set("request",mapper.valueToTree(new ExtractRequest("probe",List.of(1),List.of(c))));}
                }else if(calls.incrementAndGet()==1)response.putArray("extractions");
                else {status=HttpStatus.SERVICE_UNAVAILABLE;response.put("code","GEMINI_API_ERROR");}
                return Mono.just(ClientResponse.create(status).header("Content-Type","application/json").body(response.toString()).build());
            }).build();
            var coordinator=new BulkExtractCoordinator(client,Duration.ZERO,LocalStateFiles.optional(root,"extract"));
            if(write) {
                var model=clauses.stream().map(c->new Clause(c.clauseId(),c.riderId(),c.section(),c.article(),c.articleTitle(),c.paragraph(),c.page(),c.fromTable(),c.text())).toList();
                new DocumentStore(root).save(DocumentRecord.pending("doc",OffsetDateTime.now().plusHours(1)).withReady(1,List.of(),model));
                new AnalysisStore(root).save(AnalysisRecord.queued("probe","doc",true,null,null,1,List.of(),model).withStep(AnalysisStep.EXTRACTING));
                try {coordinator.extract(source);throw new AssertionError("Expected fixture failure");}
                catch(BulkExtractCoordinator.BulkFailure expected) { }
            }else {
                if(new DocumentStore(root).get("doc").status()!=DocumentStatus.READY)throw new AssertionError("Document lost");
                if(new AnalysisStore(root).get("probe").status()!=AnalysisStatus.FAILED)throw new AssertionError("Interrupted state lost");
                if(coordinator.progress("probe").completedParts()!=1 || !coordinator.canResume("probe"))throw new AssertionError("Checkpoint lost");
            }
            System.out.println("{\"phase\":\""+args[0]+"\",\"disk_probe\":\"passed\",\"actual_gemini_calls\":0}");
        }
    }
}
