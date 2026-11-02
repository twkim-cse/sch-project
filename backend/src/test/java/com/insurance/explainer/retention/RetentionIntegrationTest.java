package com.insurance.explainer.retention;

import com.insurance.explainer.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-21 자동 삭제가 실제로 스케줄러(@Scheduled)를 통해 동작하는지 HTTP 레벨로
 * 확인한다. 보관 시간·점검 주기를 아주 짧게(각 300ms) 줄여서, 시간이 지나면
 * 문서 조회·원문 보기(F-19)·분석 결과 조회가 전부 404 NOT_FOUND가 되는지 본다.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.mock-documents=true",
        "app.mock-documents-delay-ms=0",
        "app.mock-analysis-step-delay-min-ms=0",
        "app.mock-analysis-step-delay-max-ms=0",
        "app.document-retention-ms=300",
        "app.document-retention-sweep-ms=300"
})
class RetentionIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void 보관기한이_지나면_문서_원문_분석결과가_전부_404가_된다() {
        String documentId = uploadAndWaitReady();

        ResponseEntity<String> startResponse = rest.postForEntity(url("/api/analyses"),
                jsonRequest("{\"documentId\":\"" + documentId + "\",\"riderIds\":[],\"viewAll\":true}"),
                String.class);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String analysisId = extractField(startResponse.getBody(), "analysisId");

        // 지금은 아직 살아 있어야 한다.
        assertThat(rest.getForEntity(url("/api/documents/" + documentId), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 보관 시간(300ms) + 점검 주기(300ms)를 넉넉히 기다린다.
        waitUntil(() -> rest.getForEntity(url("/api/documents/" + documentId), String.class)
                .getStatusCode() == HttpStatus.NOT_FOUND, 10);

        ResponseEntity<String> documentAfter = rest.getForEntity(url("/api/documents/" + documentId), String.class);
        assertThat(documentAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> clauseAfter = rest.getForEntity(
                url("/api/documents/" + documentId + "/clauses/rider01-art5-p2"), String.class);
        assertThat(clauseAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> analysisStatusAfter = rest.getForEntity(
                url("/api/analyses/" + analysisId), String.class);
        assertThat(analysisStatusAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> analysisResultAfter = rest.getForEntity(
                url("/api/analyses/" + analysisId + "/result"), String.class);
        assertThat(analysisResultAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 삭제_직후_원문보기도_즉시_404가_된다() {
        String documentId = uploadAndWaitReady();

        ResponseEntity<String> clauseBefore = rest.getForEntity(
                url("/api/documents/" + documentId + "/clauses/rider01-art5-p2"), String.class);
        assertThat(clauseBefore.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clauseBefore.getBody()).contains("\"section\":\"암진단 특별약관\"");

        rest.delete(url("/api/documents/" + documentId));

        ResponseEntity<String> clauseAfter = rest.getForEntity(
                url("/api/documents/" + documentId + "/clauses/rider01-art5-p2"), String.class);
        assertThat(clauseAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String uploadAndWaitReady() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("아무 내용".getBytes()) {
            @Override
            public String getFilename() {
                return "whatever.pdf";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> uploadResponse = rest.postForEntity(
                url("/api/documents"), new HttpEntity<>(body, headers), String.class);
        String documentId = extractField(uploadResponse.getBody(), "documentId");

        waitUntil(() -> {
            String b = rest.getForEntity(url("/api/documents/" + documentId), String.class).getBody();
            return b != null && b.contains("\"status\":\"READY\"");
        }, 5);
        return documentId;
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError(timeoutSeconds + "초 안에 조건을 만족하지 못함");
    }

    private HttpEntity<String> jsonRequest(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
