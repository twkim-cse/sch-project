package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.explainer.Application;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.document.RiderInfo;
import com.insurance.explainer.structure.Clause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real JSON, mocked Gemini. Does NOT test PDF parsing or browser upload. */
@EnabledIfEnvironmentVariable(named="DENTAL_EXTRACT_JSON", matches=".+")
@SpringBootTest(classes={Application.class,DentalOfflineIntegrationTest.Offline.class},
        webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.mock-documents=false","app.mock-analysis-step-delay-min-ms=0",
                "app.mock-analysis-step-delay-max-ms=0","logging.level.root=WARN",
                "logging.level.org.springframework=WARN","logging.level.com.insurance=WARN"})
class DentalOfflineIntegrationTest {
    @DynamicPropertySource static void mockUrl(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", () -> System.getenv("DENTAL_MOCK_AI_URL"));
    }
    @TestConfiguration static class Offline {
        @Bean @Primary AiClient offlineAiClient(WebClient aiWebClient) {
            // Mock-only process: remove real waiting, never production admission limits.
            return new AiClient(aiWebClient, new BulkExtractCoordinator(aiWebClient, Duration.ZERO));
        }
    }
    @Autowired DocumentStore documents;
    @Autowired AnalysisStore analyses;
    @Autowired AiClient aiClient;
    @LocalServerPort int port;
    final ObjectMapper mapper=new ObjectMapper();
    final TestRestTemplate http=new TestRestTemplate();

    @Test void all_dental_json_parts_merge_explain_and_store_over_real_http() throws Exception {
        String mockBase=System.getenv("DENTAL_MOCK_AI_URL");
        var marker=http.getForObject(mockBase+"/mock/metrics",String.class);
        var before=mapper.readTree(marker);
        assertThat(before.path("mode").asText()).isEqualTo("MOCK_ONLY");
        byte[] original=Files.readAllBytes(Path.of(System.getenv("DENTAL_EXTRACT_JSON")));
        ExtractRequest source=mapper.readValue(original,ExtractRequest.class);
        var clauses=source.clauses().stream().map(c -> new Clause(c.clauseId(),c.riderId(),c.section(),
                c.article(),c.articleTitle(),c.paragraph(),c.page(),c.fromTable(),c.text())).toList();
        var riders=new LinkedHashMap<String,RiderInfo>();
        clauses.stream().filter(c -> c.riderId()!=null).forEach(c -> riders.putIfAbsent(c.riderId(),
                new RiderInfo(c.riderId(),"Mock scope",c.page())));
        documents.save(DocumentRecord.pending("dental-offline",OffsetDateTime.now().plusHours(1))
                .withReady(clauses.stream().mapToInt(Clause::page).max().orElse(1),riders.values().stream().toList(),clauses));
        var start=http.postForEntity("http://localhost:"+port+"/api/analyses",
                Map.of("documentId","dental-offline","viewAll",true,"riderIds",java.util.List.of()),String.class);
        assertThat(start.getStatusCode().value()).isEqualTo(202);
        String id=mapper.readTree(start.getBody()).path("analysisId").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        AnalysisRecord record;
        do {
            Thread.sleep(50);record=analyses.get(id);
        } while(record.status()!=AnalysisStatus.DONE && record.status()!=AnalysisStatus.FAILED && System.nanoTime()<deadline);
        assertThat(record.status().name()).isEqualTo("DONE");
        assertThat(record.clauses().size()).isEqualTo(source.clauses().size());
        assertThat(record.cards().size()).isGreaterThan(0);
        var result=http.getForEntity("http://localhost:"+port+"/api/analyses/"+id+"/result",String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var progress=aiClient.extractProgress(id);
        assertThat(progress.state()).isEqualTo("COMPLETED");
        var after=mapper.readTree(http.getForObject(mockBase+"/mock/metrics",String.class));
        int partCalls=after.path("extract_requests").asInt()-before.path("extract_requests").asInt();
        assertThat(partCalls).isEqualTo(progress.totalParts());
        assertThat(after.path("real_gemini_calls").asInt()).isZero();
        assertThat(after.path("b_recheck_enabled").asBoolean()).isFalse();
        assertThat(Files.readAllBytes(Path.of(System.getenv("DENTAL_EXTRACT_JSON")))).isEqualTo(original);
        String report=System.getenv("DENTAL_OFFLINE_REPORT");
        if(report!=null) Files.writeString(Path.of(report),mapper.writeValueAsString(Map.of(
                "source_clauses",clauses.size(),"http_parts",partCalls,"status",record.status().name(),
                "result_http",result.getStatusCode().value(),"mock_cards",record.cards().size(),
                "mock_generation_calls",after.path("generation_attempts").asInt()-before.path("generation_attempts").asInt(),
                "mock_review_calls",after.path("review_attempts").asInt()-before.path("review_attempts").asInt(),
                "actual_gemini_calls",0,"pdf_upload_tested",false,"browser_tested",false)));
    }
}
