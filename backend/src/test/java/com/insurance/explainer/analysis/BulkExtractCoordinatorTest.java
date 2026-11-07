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
    private int plannedInputBytes=10_000;
    private boolean duplicatePartIds;
    private boolean oversizedPart;
    private String retryAfterHeader;
    private ExtractRequest source;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path disk;

    private BulkExtractCoordinator durable() {
        return new BulkExtractCoordinator(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build(),
                java.time.Duration.ZERO,new com.insurance.explainer.persistence.LocalStateFiles(disk.resolve("extract")));
    }

    @BeforeEach void setUp() throws Exception {
        source = new ExtractRequest("synthetic", List.of(1), List.of(
                clause("one"), clause("two"), clause("three")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes="{\"model\":\"offline-model\",\"ai_configured\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/ai/extract", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            ObjectNode response = mapper.createObjectNode();
            int status = 200;
            if (path.endsWith("/plan")) {
                response.put("plan_id", "plan");response.put("preservation_verified", true);
                ArrayNode parts = response.putArray("parts");
                for (ClauseDto c : source.clauses()) {
                    ObjectNode p = parts.addObject();p.put("part_id", duplicatePartIds ? "duplicate" : c.clauseId());p.put("input_bytes", plannedInputBytes);
                    p.set("request", mapper.valueToTree(new ExtractRequest("synthetic", List.of(1), List.of(c))));
                    if(oversizedPart) {
                        ArrayNode many=(ArrayNode)p.path("request").path("clauses");
                        for(int i=0;i<25;i++)many.add(mapper.valueToTree(clause("extra-"+i)));
                    }
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
                    if(retryAfterHeader!=null)exchange.getResponseHeaders().set("Retry-After",retryAfterHeader);
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
        coordinator=new BulkExtractCoordinator(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build(), java.time.Duration.ZERO);
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
        assertThat(coordinator.progress(source.analysisId()).failedClauseIds()).isEmpty();
        assertThat(coordinator.progress(source.analysisId()).fullDocumentSuccess()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={429,503,504})
    void transient_failure_is_partial_until_explicit_resume(int status) {
        failureStatus=status;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(coordinator.progress(source.analysisId()).state()).isEqualTo("PARTIAL");
        assertThat(coordinator.progress(source.analysisId()).fullDocumentSuccess()).isFalse();
        assertThat(coordinator.progress(source.analysisId()).failedClauseIds()).containsExactly("two");
        assertThat(coordinator.canResume(source.analysisId())).isTrue();
        coordinator.extract(source);
        assertThat(coordinator.progress(source.analysisId()).state()).isEqualTo("COMPLETED");
        assertThat(calls.get("one").get()).isEqualTo(1);
    }

    @Test void validation_422_does_not_repeat_or_merge() {
        failureStatus=422;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(coordinator.canResume(source.analysisId())).isFalse();
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("two").get()).isEqualTo(1);
        assertThat(calls).doesNotContainKey("merge");
    }

    @Test void deleting_analysis_drops_checkpoint() {
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        coordinator.forget(source.analysisId());
        assertThat(coordinator.progress(source.analysisId())).isNull();
    }

    @Test void byte_limit_in_plan_is_checked_before_any_extract() {
        plannedInputBytes=24_001;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls).isEmpty();
    }

    @Test void clause_count_in_plan_is_checked_before_any_extract() {
        oversizedPart=true;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls).isEmpty();
    }

    @Test void duplicate_part_ids_are_not_silently_overwritten() {
        duplicatePartIds=true;
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls).isEmpty();
    }

    @Test void upstream_cooldown_blocks_immediate_resume_without_resending_successes() {
        failureStatus=502;retryAfterHeader="60";
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        var progress=coordinator.progress(source.analysisId());
        assertThat(progress.state()).isEqualTo("PARTIAL");
        assertThat(progress.retryAfterSeconds()).isBetween(1L,60L);
        assertThat(progress.resumable()).isFalse();
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("one").get()).isEqualTo(1);
        assertThat(calls.get("two").get()).isEqualTo(1);
        assertThat(calls).doesNotContainKeys("three","merge");
    }

    @Test void invalid_retry_after_does_not_echo_header_or_block_valid_explicit_resume() {
        retryAfterHeader="SECRET-invalid";
        assertThatThrownBy(()->coordinator.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(coordinator.canResume(source.analysisId())).isTrue();
        assertThat(coordinator.extract(source).extractions()).hasSize(3);
    }

    @Test void new_coordinator_restores_completed_parts_and_attempt_budget_from_disk() {
        var first=durable();
        assertThatThrownBy(()->first.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        var restarted=durable();
        assertThat(restarted.progress(source.analysisId()).completedParts()).isEqualTo(1);
        assertThat(restarted.extract(source).extractions()).hasSize(3);
        assertThat(calls.get("one").get()).isEqualTo(1);
        assertThat(calls.get("two").get()).isEqualTo(2);
    }

    @Test void restart_does_not_forget_upstream_cooldown() {
        retryAfterHeader="120";var first=durable();
        assertThatThrownBy(()->first.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        var restarted=durable();
        assertThat(restarted.progress(source.analysisId()).retryAfterSeconds()).isGreaterThan(110);
        assertThat(restarted.canResume(source.analysisId())).isFalse();
        assertThatThrownBy(()->restarted.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("two").get()).isEqualTo(1);
    }

    @Test void interrupted_inflight_request_is_not_blindly_reissued_after_restart() {
        var first=durable();
        assertThatThrownBy(()->first.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        var files=new com.insurance.explainer.persistence.LocalStateFiles(disk.resolve("extract"));
        var saved=(ObjectNode)files.read(source.analysisId());saved.put("in_flight",true);files.write(source.analysisId(),saved);
        var restarted=durable();
        assertThat(restarted.canResume(source.analysisId())).isFalse();
        assertThat(restarted.progress(source.analysisId()).errorCode()).isEqualTo("BULK_CLIENT_ERROR");
        assertThatThrownBy(()->restarted.extract(source)).isInstanceOf(BulkExtractCoordinator.BulkFailure.class);
        assertThat(calls.get("one").get()).isEqualTo(1);assertThat(calls.get("two").get()).isEqualTo(1);
    }
}
