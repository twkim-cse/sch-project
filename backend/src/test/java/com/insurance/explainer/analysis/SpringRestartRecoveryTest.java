package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.explainer.Application;
import com.insurance.explainer.document.*;
import com.insurance.explainer.structure.Clause;
import com.insurance.explainer.persistence.LocalStateFiles;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real Spring HTTP/context restart, mock upstream only. No PDF or Gemini calls. */
class SpringRestartRecoveryTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    final Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();
    @TestConfiguration static class FastOffline {
        @Bean @Primary AiClient durableOffline(WebClient client,@Value("${ai.checkpoint-dir}") String root) {
            return new AiClient(client,new BulkExtractCoordinator(client,Duration.ZERO,LocalStateFiles.optional(root,"extract")),
                    new DurableExplainCalls(client,LocalStateFiles.optional(root,"explain")),true);
        }
    }
    ServletWebServerApplicationContext boot(String base) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(Application.class,FastOffline.class).run(
                "--server.port=0","--ai.base-url="+base,"--ai.checkpoint-dir="+directory,
                "--app.mock-documents=false","--logging.level.root=OFF","--logging.level.org.springframework=OFF");
    }
    @Test void partial_analysis_survives_restart_and_only_unfinished_parts_are_dispatched() throws Exception {
        HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/",exchange->{
            var response=mapper.createObjectNode();int status=200;String path=exchange.getRequestURI().getPath();
            if(path.equals("/health")) {response.put("model","offline-model");response.put("ai_configured",true);}
            else {
                var input=mapper.readTree(exchange.getRequestBody());
                if(path.endsWith("/plan")) {
                    response.put("plan_id","offline-plan");response.put("preservation_verified",true);
                    var parts=response.putArray("parts");
                    for(var c:input.path("clauses")) {
                        var part=parts.addObject();part.put("part_id",c.path("clause_id").asText());part.put("input_bytes",10_000);
                        var request=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)request).putArray("clauses").add(c);
                        part.set("request",request);
                    }
                } else if(path.endsWith("/merge"))response.putArray("extractions");
                else {
                    String id=input.path("clauses").get(0).path("clause_id").asText();
                    int count=calls.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
                    if(id.equals("two") && count==1) {status=503;response.put("code","GEMINI_API_ERROR");}
                    else response.putArray("extractions");
                }
            }
            byte[] bytes=mapper.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();String base="http://127.0.0.1:"+upstream.getAddress().getPort();String id;
        try {
            try(var first=boot(base)) {
                var docs=first.getBean(DocumentStore.class);
                var clauses=List.of("one","two","three").stream().map(c->new Clause(c,null,"synthetic",c,null,null,1,false,"Synthetic fixture.")).toList();
                docs.save(DocumentRecord.pending("doc",OffsetDateTime.now().plusHours(1)).withReady(1,List.of(),clauses));
                var http=new TestRestTemplate();String url="http://localhost:"+first.getWebServer().getPort();
                var start=http.postForEntity(url+"/api/analyses",Map.of("documentId","doc","viewAll",true),String.class);
                id=mapper.readTree(start.getBody()).path("analysisId").asText();
                waitFor(first.getBean(AnalysisStore.class),id,AnalysisStatus.FAILED);
                assertThat(first.getBean(AiClient.class).extractProgress(id).state()).isEqualTo("PARTIAL");
                assertThat(http.getForEntity(url+"/api/analyses/"+id+"/result",String.class).getStatusCode().value()).isEqualTo(409);
            }
            try(var restarted=boot(base)) {
                assertThat(restarted.getBean(DocumentStore.class).get("doc").status()).isEqualTo(DocumentStatus.READY);
                var http=new TestRestTemplate();String url="http://localhost:"+restarted.getWebServer().getPort();
                assertThat(http.postForEntity(url+"/api/analyses/"+id+"/resume",null,String.class).getStatusCode().value()).isEqualTo(202);
                waitFor(restarted.getBean(AnalysisStore.class),id,AnalysisStatus.DONE);
                assertThat(http.getForEntity(url+"/api/analyses/"+id+"/result",String.class).getStatusCode().value()).isEqualTo(200);
            }
            try(var last=boot(base)) {
                assertThat(last.getBean(AnalysisStore.class).get(id).status()).isEqualTo(AnalysisStatus.DONE);
                assertThat(last.getBean(AnalysisStore.class).get(id).clauses()).hasSize(3);
            }
            assertThat(calls.get("one").get()).isEqualTo(1);
            assertThat(calls.get("two").get()).isEqualTo(2);
            assertThat(calls.get("three").get()).isEqualTo(1);
        }finally{upstream.stop(0);}
    }
    void waitFor(AnalysisStore store,String id,AnalysisStatus target) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(store.get(id).status()!=target && System.nanoTime()<until)Thread.sleep(20);
        assertThat(store.get(id).status()).isEqualTo(target);
    }
}
