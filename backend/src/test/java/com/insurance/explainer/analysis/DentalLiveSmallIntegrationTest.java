package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.explainer.Application;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStore;
import com.insurance.explainer.structure.Clause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in REAL Gemini. Reuses already parsed PDF data, no upload repeat.
 * One-clause scope, NOT a complete-document or browser E2E test.
 */
@EnabledIfEnvironmentVariable(named="DENTAL_LIVE_SMALL_JSON",matches=".+")
@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.mock-documents=false","logging.level.root=WARN","logging.level.org.springframework=WARN"})
class DentalLiveSmallIntegrationTest {
    @DynamicPropertySource static void liveUrl(DynamicPropertyRegistry properties) {
        properties.add("ai.base-url",()->System.getenv("DENTAL_LIVE_AI_URL"));
    }
    @Autowired DocumentStore documents;
    @Autowired AnalysisStore analyses;
    @LocalServerPort int port;
    final ObjectMapper mapper=new ObjectMapper();
    final TestRestTemplate http=new TestRestTemplate();

    @Test void real_small_analysis_is_saved_and_queried_without_reparsing_pdf() throws Exception {
        String base=System.getenv("DENTAL_LIVE_AI_URL");
        var before=mapper.readTree(http.getForObject(base+"/local-test/metrics",String.class));
        assertThat(before.path("mode").asText()).isEqualTo("REAL_GEMINI_RESILIENCE");
        assertThat(before.path("model").asText()).isEqualTo("gemini-3.6-flash");
        var source=mapper.readValue(Files.readAllBytes(Path.of(System.getenv("DENTAL_LIVE_SMALL_JSON"))),ExtractRequest.class);
        // Exact already-verified real clause; this is a test scope, not a production exception.
        var c=source.clauses().stream().filter(row -> row.clauseId().equals(System.getenv("DENTAL_LIVE_CLAUSE_ID")) && row.riderId()==null).findFirst().orElseThrow();
        var clause=new Clause(c.clauseId(),c.riderId(),c.section(),c.article(),c.articleTitle(),c.paragraph(),c.page(),c.fromTable(),c.text());
        documents.save(DocumentRecord.pending("dental-live-scope",OffsetDateTime.now().plusHours(1))
                .withReady(c.page(),List.of(),List.of(clause)));
        long started=System.nanoTime();
        var start=http.postForEntity("http://localhost:"+port+"/api/analyses",
                Map.of("documentId","dental-live-scope","viewAll",true,"riderIds",List.of()),String.class);
        assertThat(start.getStatusCode().value()).isEqualTo(202);
        String id=mapper.readTree(start.getBody()).path("analysisId").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(420).toNanos();
        AnalysisRecord record;
        do {Thread.sleep(200);record=analyses.get(id);}
        while(record.status()!=AnalysisStatus.DONE && record.status()!=AnalysisStatus.FAILED && System.nanoTime()<deadline);
        var result=http.getForEntity("http://localhost:"+port+"/api/analyses/"+id+"/result",String.class);
        var after=mapper.readTree(http.getForObject(base+"/local-test/metrics",String.class));
        var summary=Map.of("status",record.status().name(),"start_http",start.getStatusCode().value(),
                "result_http",result.getStatusCode().value(),"cards",record.cards().size(),
                "full_cards",record.cards().stream().filter(card -> "FULL".equals(card.display())).count(),
                "quote_only_cards",record.cards().stream().filter(card -> "QUOTE_ONLY".equals(card.display())).count(),
                "actual_generation_attempts",after.path("attempts").asInt()-before.path("attempts").asInt(),
                "elapsed_ms",(System.nanoTime()-started)/1_000_000,"whole_document",false);
        Files.writeString(Path.of(System.getenv("DENTAL_LIVE_REPORT")),mapper.writeValueAsString(summary));
        assertThat(record.status().name()).isEqualTo("DONE");
        assertThat(record.cards().size()).isGreaterThan(0);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(after.path("attempts").asInt()).isLessThanOrEqualTo(8);
    }
}
