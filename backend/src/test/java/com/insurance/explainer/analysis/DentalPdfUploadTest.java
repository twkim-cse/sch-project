package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.explainer.Application;
import com.insurance.explainer.analysis.ai.ClauseDto;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.document.DocumentStatus;
import com.insurance.explainer.document.DocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual multipart upload and PDFBox parsing; no AI calls or fabricated clauses. */
@EnabledIfEnvironmentVariable(named="DENTAL_PDF_PATH", matches=".+")
@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.mock-documents=false","ai.base-url=http://127.0.0.1:1",
                "logging.level.root=WARN","logging.level.org.springframework=WARN"})
class DentalPdfUploadTest {
    @Autowired DocumentStore documents;
    @LocalServerPort int port;
    final ObjectMapper mapper=new ObjectMapper();
    final TestRestTemplate http=new TestRestTemplate();

    @Test void actual_pdf_upload_generates_source_json_without_gemini() throws Exception {
        Path pdf=Path.of(System.getenv("DENTAL_PDF_PATH"));byte[] before=Files.readAllBytes(pdf);
        var multipart=new LinkedMultiValueMap<String,Object>();
        multipart.add("file",new FileSystemResource(pdf));
        var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var uploaded=http.postForEntity("http://localhost:"+port+"/api/documents",new HttpEntity<>(multipart,headers),String.class);
        assertThat(uploaded.getStatusCode().value()).isEqualTo(202);
        String id=mapper.readTree(uploaded.getBody()).path("documentId").asText();
        long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        while(documents.get(id).status()==DocumentStatus.PENDING && System.nanoTime()<end)Thread.sleep(100);
        var document=documents.get(id);
        assertThat(document.status().name()).isEqualTo("READY");
        assertThat(document.pageCount()).isEqualTo(256);
        var status=http.getForEntity("http://localhost:"+port+"/api/documents/"+id,String.class);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        var clauses=document.clauses().stream().map(c -> new ClauseDto(c.clauseId(),c.riderId(),c.section(),
                c.article(),c.articleTitle(),c.paragraph(),c.page(),c.fromTable(),c.text())).toList();
        assertThat(clauses.size()).isGreaterThan(0);
        Path output=Path.of(System.getenv("DENTAL_PDF_EXPORT"));
        Files.write(output,mapper.writeValueAsBytes(new ExtractRequest("dental-actual-spring-pdf",List.of(1,2,3,4,5,6),clauses)));
        Files.writeString(output.resolveSibling("pdf_upload_summary.json"),mapper.writeValueAsString(java.util.Map.of(
                "upload_http",202,"status_http",200,"document_status","READY","pages",document.pageCount(),
                "parsed_clauses",clauses.size(),"riders",document.riders().size(),"real_gemini_calls",0)));
        assertThat(Files.readAllBytes(pdf)).isEqualTo(before);
    }
}
