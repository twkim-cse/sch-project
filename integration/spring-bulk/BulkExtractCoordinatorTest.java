package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insurance.explainer.analysis.ai.ClauseDto;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Local HTTP stub only; no Gemini, real policies, or secrets. */
class BulkExtractCoordinatorTest {
    private HttpServer server;
    private BulkExtractCoordinator coordinator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private int failureStatus = 503;
    private boolean mergeFailure;
    private ExtractRequest source;

    @BeforeEach void setUp() throws Exception {
        source = new ExtractRequest("synthetic", List.of(1), List.of(
                clause("one"), clause("two"), clause("three")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/extract", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            ObjectNode response = mapper.createObjectNode();
            int status = 200;
            if (path.endsWith("/plan")) {
                response.put("plan_id", "plan");response.put("preservation_verified", true);
                ArrayNode parts = response.putArray("parts");
                for (ClauseDto c : source.clauses()) {
                    ObjectNode p = parts.addObject();p.put("part_id", c.clauseId());
                    p.set("request", mapper.valueToTree(new ExtractRequest("synthetic", List.of(1), List.of(c))));
                }
            } else if (path.endsWith("/merge")) {
                calls.computeIfAbsent("merge", k -> new AtomicInteger()).incrementAndGet();
                if (mergeFailure) { status=409;response.put("code", "BULK_INCOMPLETE"); }
                else {
                    ArrayNode rows=response.putArray("extractions");
                    for (ClauseDto c:source.clauses()) {
                        ObjectNode row=rows.addObject();row.put("check_item",1);row.put("clause_id",c.clauseId());row.put("quote",c.text());
                    }
                }
            } else {
                String id=body.path("clauses").get(0).path("clause_id").asText();
                int count=calls.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
                if (id.equals("two") && count==1) {
                    status=failureStatus;response.put("code",status==413 ? "EXTRACT_INPUT_BUDGET_EXCEEDED" : "GEMINI_API_ERROR");
                } else {
                    ObjectNode row=response.putArray("extractions").addObject();
                    row.put("check_item",1);row.put("clause_id",id);
                    row.put("quote",body.path("clauses").get(0).path("text").asText());
                }
            }
            byte[] bytes=mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        coordinator=new BulkExtractCoordinator(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
    }

    private ClauseDto clause(String id) { return new ClauseDto(id,null,"synthetic","article","title",null,1,false,"Synthetic clause."); }
    @AfterEach void stop() { if(server!=null)server.stop(0); }

    @Test void stops_then_resumes_only_failed_and_later_parts() {
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("one").get()).isEqualTo(1);
        assertThat(calls).doesNotContainKeys("three","merge");
        assertThat(coordinator.extract(source).extractions()).hasSize(3);
        assertThat(calls.get("one").get()).isEqualTo(1);
        assertThat(calls.get("two").get()).isEqualTo(2);
    }

    @Test void budget_413_is_not_retried_on_resume() {
        failureStatus=413;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("two").get()).isEqualTo(1);
        assertThat(calls).doesNotContainKeys("three","merge");
    }

    @Test void merge_failure_never_returns_partial_success() {
        failureStatus=503;mergeFailure=true;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("merge").get()).isEqualTo(1);
    }
}
