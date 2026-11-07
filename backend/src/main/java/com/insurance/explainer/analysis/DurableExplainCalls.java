package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insurance.explainer.analysis.ai.*;
import com.insurance.explainer.persistence.LocalStateFiles;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Cache only complete, validated responses. Never synthesize review pass.
 * Ambiguous in-flight/network outcomes block automatic replay, even on restart.
 */
final class DurableExplainCalls {
    private final WebClient client;
    private final LocalStateFiles files;
    private final Map<String,Object> locks=new ConcurrentHashMap<>();
    private final Map<String,Long> deleted=new ConcurrentHashMap<>();
    private final Object diskLock=new Object();
    DurableExplainCalls(WebClient client,LocalStateFiles files){this.client=client;this.files=files;}

    private ObjectNode load(String id,String model) {
        JsonNode node=files.read(id);
        if(node==null) {
            var value=(ObjectNode)files.tree(Map.of("analysis_id",id,"model",model,"entries",Map.of()));return value;
        }
        if(!node.path("analysis_id").asText().equals(id) || !node.path("model").asText().equals(model)
                || !node.path("entries").isObject())throw new AiCallFailedException("해설 체크포인트 설정이 일치하지 않습니다.",null);
        return (ObjectNode)node;
    }
    private void save(String id,ObjectNode value) {
        synchronized(diskLock) {
            if(deleted.containsKey(id))throw new AiCallFailedException("삭제된 분석 작업입니다.",null);
            files.write(id,value);
        }
    }
    ExplainResponse call(ExplainRequest request,Supplier<ExplainResponse> action) {
        if(files==null)return action.get();
        String id=request.analysisId();
        synchronized(locks.computeIfAbsent(id,k->new Object())) {
            String model=AiModelIdentity.read(client);
            ObjectNode saved=load(id,model);ObjectNode entries=(ObjectNode)saved.path("entries");
            JsonNode body=files.tree(request);String key=files.fingerprint(body);
            JsonNode previous=entries.get(key);
            if(previous!=null) {
                if(previous.path("attempts").asInt()<1 || previous.path("attempts").asInt()>2)
                    throw new AiCallFailedException("해설 체크포인트 시도 횟수가 올바르지 않습니다.",null);
                if(!previous.path("request").equals(body))throw new AiCallFailedException("해설 요청이 일치하지 않습니다.",null);
                if("succeeded".equals(previous.path("state").asText())) {
                    ExplainResponse result=files.value(previous.path("response"),ExplainResponse.class);validate(request,result);return result;
                }
                if(!"failed".equals(previous.path("state").asText()) || !previous.path("retryable").asBoolean()
                        || previous.path("attempts").asInt()>=2 || previous.path("retry_after_millis").asLong()>System.currentTimeMillis())
                    throw new AiCallFailedException("해설 요청의 완료 여부 또는 재개 예산을 확인해야 합니다.",null);
            }
            ObjectNode entry=entries.putObject(key);entry.set("request",body);entry.put("state","in_flight");
            entry.put("attempts",previous==null ? 1 : previous.path("attempts").asInt()+1);
            save(id,saved); // Must commit intent before sending an upstream request.
            try {
                ExplainResponse response=action.get();validate(request,response);
                entry.set("response",files.tree(response));entry.put("state","succeeded");save(id,saved);return response;
            }catch(Exception error) {
                Throwable cause=error instanceof AiCallFailedException && error.getCause()!=null ? error.getCause() : error;
                boolean retryable=false;
                long waitMillis=60_000;
                if(cause instanceof WebClientResponseException http) {
                    int status=http.getStatusCode().value();retryable=status==429 || status==503 || status==504;
                    if(status==502)try {
                        String code=new com.fasterxml.jackson.databind.ObjectMapper().readTree(http.getResponseBodyAsString()).path("code").asText();
                        retryable=Set.of("GEMINI_API_ERROR","GEMINI_TIMEOUT").contains(code);
                    }catch(Exception ignored){}
                    entry.put("state","failed");
                    String retryAfter=http.getHeaders().getFirst("Retry-After");
                    if(retryAfter!=null) {
                        try {
                            long seconds=Long.parseLong(retryAfter);
                            if(seconds>=0 && seconds<=2_147_483_647L)waitMillis=Math.max(waitMillis,seconds*1000);
                        }catch(NumberFormatException ignored) {
                            try {
                                long date=java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                                waitMillis=Math.max(waitMillis,date-System.currentTimeMillis()+1000);
                            }catch(java.time.format.DateTimeParseException ignoredDate){}
                        }
                    }
                } else entry.put("state","unknown");
                entry.put("retryable",retryable);entry.put("retry_after_millis",System.currentTimeMillis()+waitMillis);
                save(id,saved);
                throw new AiCallFailedException("해설 요청이 완료되지 않았습니다. 체크포인트를 확인해 주세요.",null);
            }
        }
    }
    private static void validate(ExplainRequest request,ExplainResponse response) {
        if(response==null || response.cards()==null || response.cards().size()!=request.items().size()
                || !response.cards().stream().map(CardDto::itemId).collect(Collectors.toSet())
                .equals(request.items().stream().map(ExplainItemDto::itemId).collect(Collectors.toSet())))
            throw new AiCallFailedException("해설 응답 항목이 일치하지 않습니다.",null);
        for(CardDto card:response.cards())if(card.title()==null || card.title().isBlank() || card.explanation()==null || card.explanation().isBlank()
                || !Set.of("pass","fail").contains(card.review())
                || ("pass".equals(card.review()) && card.reviewReason()!=null)
                || ("fail".equals(card.review()) && (card.reviewReason()==null || card.reviewReason().isBlank())))
            throw new AiCallFailedException("해설 검토 응답이 올바르지 않습니다.",null);
    }
    boolean canResume(String id) {
        if(files==null)return false;JsonNode saved=files.read(id);if(saved==null)return true;
        boolean found=false;
        for(JsonNode entry:saved.path("entries")) {
            found=true;
            if("succeeded".equals(entry.path("state").asText()))continue;
            if(!"failed".equals(entry.path("state").asText()) || !entry.path("retryable").asBoolean()
                    || entry.path("attempts").asInt()>=2 || entry.path("retry_after_millis").asLong()>System.currentTimeMillis())return false;
        }
        return found;
    }
    void forget(String id) {
        if(files==null)return;
        deleted.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis()-java.time.Duration.ofHours(24).toMillis());
        synchronized(diskLock) {deleted.put(id,System.currentTimeMillis());files.delete(id);}
        locks.remove(id);
    }
}
