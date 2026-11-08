package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.*;
import com.insurance.explainer.Application;
import com.insurance.explainer.document.*;
import com.insurance.explainer.structure.Clause;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real local PDF + Spring + real FastAPI routes with fabricated Gemini only. */
@EnabledIfEnvironmentVariable(named="PR1920_MOCK_URL",matches=".+")
@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"app.mock-documents=false","logging.level.root=WARN","logging.level.org.springframework=WARN"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Pr1920HttpIntegrationTest {
 static String parsedDocumentId;
 @DynamicPropertySource static void url(DynamicPropertyRegistry r){r.add("ai.base-url",()->System.getenv("PR1920_MOCK_URL"));}
 @LocalServerPort int port;
 @Autowired DocumentStore documents;
 @Autowired AnalysisStore analyses;
 final TestRestTemplate http=new TestRestTemplate();final ObjectMapper mapper=new ObjectMapper();
 String base(){return "http://127.0.0.1:"+port;}
 String ai(){return System.getenv("PR1920_MOCK_URL");}
 boolean safe(){return !"false".equals(System.getenv("PR1920_EXPECT_SAFE_FAILURE"));}
 JsonNode metrics()throws Exception{return mapper.readTree(http.getForObject(ai()+"/test/metrics",String.class));}
 AnalysisRecord run(String document)throws Exception{
  var start=http.postForEntity(base()+"/api/analyses",Map.of("documentId",document,"viewAll",true),String.class);
  assertThat(start.getStatusCode().value()).isEqualTo(202);String id=mapper.readTree(start.getBody()).path("analysisId").asText();
  long end=System.nanoTime()+Duration.ofSeconds(90).toNanos();AnalysisRecord r;
  do{Thread.sleep(50);r=analyses.get(id);}while((r.status()==AnalysisStatus.RUNNING||r.status()==AnalysisStatus.QUEUED)&&System.nanoTime()<end);
  return r;
 }
 void report(String name,Map<String,Object> report)throws Exception{
  String root=System.getenv("PR1920_REPORT_DIR");if(root!=null){var dir=Path.of(root);Files.createDirectories(dir);mapper.writeValue(dir.resolve(name+".json").toFile(),report);}
 }
 @Test @Order(1) void actual_pdf_upload_parse_candidate_extract_merge_and_result()throws Exception{
  http.postForObject(ai()+"/test/control",Map.of("code",0),String.class);
  var body=new LinkedMultiValueMap<String,Object>();body.add("file",new FileSystemResource(System.getenv("PR1920_PDF")));
  var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
  var upload=http.postForEntity(base()+"/api/documents",new HttpEntity<>(body,headers),String.class);
  assertThat(upload.getStatusCode().value()).isEqualTo(202);String id=mapper.readTree(upload.getBody()).path("documentId").asText();
  long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();DocumentRecord doc;
  do{Thread.sleep(100);doc=documents.get(id);}while(doc.status()==DocumentStatus.PENDING&&System.nanoTime()<end);
  assertThat(doc.status()).isEqualTo(DocumentStatus.READY);assertThat(doc.pageCount()).isEqualTo(256);
  assertThat(doc.clauses()).hasSize(1472);
  parsedDocumentId=id;
  long start=System.nanoTime();var r=run(id);var m=metrics();
  var result=http.getForEntity(base()+"/api/analyses/"+r.analysisId()+"/result",String.class);
  report("full_pdf_"+(safe()?"safe":"baseline"),Map.of("state",r.status().name(),"result_http",result.getStatusCode().value(),
   "source_clauses",doc.clauses().size(),"pages",doc.pageCount(),"cards",r.cards().size(),"metrics",m,
   "elapsed_ms",(System.nanoTime()-start)/1_000_000));
  if(!safe()){assertThat(r.status()).isEqualTo(AnalysisStatus.FAILED);return;}
  assertThat(r.status()).isEqualTo(AnalysisStatus.DONE);assertThat(result.getStatusCode().value()).isEqualTo(200);
  assertThat(m.path("parts").asInt()).isGreaterThan(1);assertThat(m.path("failed_calls").asInt()).isZero();
  assertThat(m.path("extract_calls").asInt()).isEqualTo(m.path("parts").asInt());assertThat(m.path("merge_calls").asInt()).isEqualTo(1);
  assertThat(m.path("invalid_part_budget").asInt()).isZero();assertThat(m.path("policy_version").asText()).isEqualTo("recall-rules-2");
  assertThat(m.path("metadata_verified").asBoolean()).isTrue();assertThat(m.path("quote_verified").asBoolean()).isTrue();
  assertThat(m.path("contract_verified").asBoolean()).isTrue();assertThat(m.path("real_gemini_calls").asInt()).isZero();
  assertThat(m.path("generation_attempts").asInt()).isEqualTo(r.cards().size());
  assertThat(m.path("review_attempts").asInt()).isEqualTo(r.cards().size());
  for(JsonNode response:m.path("explain_responses")){
   assertThat(response.path("http").asInt()).isEqualTo(200);
   assertThat(response.path("pass").asInt()).isEqualTo(response.path("items").asInt());
  }
  var checkpoint=analyses.candidateCheckpoint(r.analysisId());
  assertThat(checkpoint.coverage()).isEqualTo(m.path("last_coverage"));
  assertThat(checkpoint.coverage().path("unselected_count").asInt()).isEqualTo(1472-m.path("candidate_count").asInt());
  var scoped=mapper.readTree(http.getForObject(base()+"/api/analyses/"+r.analysisId()+"/candidate-progress",String.class));
  assertThat(scoped.path("state").asText()).isEqualTo("CANDIDATES_COMPLETE");
  assertThat(scoped.path("coverage")).isEqualTo(checkpoint.coverage());
  Map<String,Clause> source=new HashMap<>();doc.clauses().forEach(c->source.put(c.clauseId(),c));
  for(var card:r.cards()){
   var c=source.get(card.clauseId());assertThat(c).isNotNull();assertThat(c.riderId()).isEqualTo(card.riderId());
   assertThat(c.page()).isEqualTo(card.page());assertThat(c.section()).isEqualTo(card.section());
   assertThat(c.article()).isEqualTo(card.article());assertThat(c.paragraph()).isEqualTo(card.paragraph());
   assertThat(c.fromTable()).isEqualTo(card.fromTable());
   assertThat(new QuoteValidatorImpl().isQuoteFoundInClause(new com.insurance.explainer.analysis.ai.ExtractionDto(card.checkItem(),card.clauseId(),card.quote()),c)).isTrue();
  }
 }
 @Test @Order(2) void failed_required_part_never_becomes_done_or_not_found()throws Exception{
  for(int code:new int[]{503,502,413,422,429,504}){
   http.postForObject(ai()+"/test/control",Map.of("code",code),String.class);
   var rows=new ArrayList<Clause>();for(int i=0;i<56;i++)rows.add(new Clause("synthetic-"+i,null,"synthetic","article-"+i,"보험금을 지급하지 않는 사유",null,1,false,"회사는 고의로 발생한 손해에 대해서는 보험금을 지급하지 않습니다."));
   String id="fault-"+code;documents.save(DocumentRecord.pending(id,OffsetDateTime.now().plusHours(1)).withReady(1,List.of(),rows));
   var r=run(id);var m=metrics();var result=http.getForEntity(base()+"/api/analyses/"+r.analysisId()+"/result",String.class);
   report("fault_"+code+"_"+(safe()?"safe":"baseline"),Map.of("state",r.status().name(),"result_http",result.getStatusCode().value(),"metrics",m,
    "not_found_in_result",result.getBody()!=null&&result.getBody().contains("NOT_FOUND")));
   if(safe()){
    assertThat(r.status()).isEqualTo(AnalysisStatus.FAILED);assertThat(result.getStatusCode().value()).isEqualTo(409);
    assertThat(m.path("merge_calls").asInt()).isZero();assertThat(m.path("explain_calls").asInt()).isZero();
    assertThat(m.path("later_after_failure").asInt()).isZero();assertThat(m.path("failed_empty_submitted").asInt()).isZero();
    assertThat(m.path("failed_calls").asInt()).isEqualTo(1);
    var cp=analyses.candidateCheckpoint(r.analysisId());assertThat(cp.state()).isEqualTo("PARTIAL");
    assertThat(cp.succeeded()).hasSize(1);assertThat(cp.failedPartId()).isNotNull();
    assertThat(cp.summary().get("resumable")).isEqualTo(false);
   }else{
    assertThat(r.status()).isEqualTo(AnalysisStatus.DONE);assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(m.path("failed_empty_submitted").asInt()).isEqualTo(1);assertThat(m.path("failed_calls").asInt()).isEqualTo(2);
    assertThat(m.path("later_after_failure").asInt()).isGreaterThan(0);assertThat(result.getBody()).contains("NOT_FOUND");
   }
  }
 }
 @Test @Order(3) void large_plan_reproduces_default_webclient_256k_limit()throws Exception{
  http.postForObject(ai()+"/test/control",Map.of("code",0),String.class);
  var doc=documents.get(parsedDocumentId);
  var rows=doc.clauses().stream().map(c->new com.insurance.explainer.analysis.ai.ClauseDto(c.clauseId(),c.riderId(),c.section(),c.article(),c.articleTitle(),c.paragraph(),c.page(),c.fromTable(),c.text())).toList();
  var source=new com.insurance.explainer.analysis.ai.ExtractRequest("buffer-probe",List.of(1,2,3,4,5,6),rows);
  var client=org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(ai()).build();
  assertThatThrownBy(()->client.post().uri("/ai/extract/candidates/plan").bodyValue(Map.of("source_request",source,"mode","recall"))
   .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30)))
   .hasRootCauseInstanceOf(org.springframework.core.io.buffer.DataBufferLimitException.class);
 }
}
