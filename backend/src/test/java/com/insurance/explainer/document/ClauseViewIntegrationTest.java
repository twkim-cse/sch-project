package com.insurance.explainer.document;

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
 * F-19 원문 보기(GET /api/documents/{documentId}/clauses/{clauseId})를 HTTP
 * 레벨로 확인한다. MOCK_DOCUMENTS=true로 빠르게 READY 문서를 만들어 검증한다
 * (실제 분석 경로의 조항 추출은 DocumentStructureAnalyzer 쪽 테스트가 다룬다).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.mock-documents=true", "app.mock-documents-delay-ms=0"})
class ClauseViewIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void 명세서_4장_형식대로_조항_전문을_돌려준다() {
        String documentId = uploadAndWaitReady();

        ResponseEntity<String> response = rest.getForEntity(
                url("/api/documents/" + documentId + "/clauses/rider01-art5-p2"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"clauseId\":\"rider01-art5-p2\"");
        assertThat(body).contains("\"section\":\"암진단 특별약관\"");
        assertThat(body).contains("\"article\":\"제5조\"");
        assertThat(body).contains("\"articleTitle\":\"보험금 지급에 관한 세부규정\"");
        assertThat(body).contains("\"paragraph\":\"제2항\"");
        assertThat(body).contains("\"page\":87");
        // 인용 대조(F-10)에 쓴 것과 같은 정리 규칙을 거친 text라, 줄바꿈 없이 한 줄로
        // 내려온다 — 프론트가 quote와 같은 부분을 공백 차이로 못 찾는 일이 없게 한다.
        assertThat(body).contains(
                "\"text\":\"암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.\"");
    }

    @Test
    void 없는_문서면_404_NOT_FOUND() {
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/documents/no-such-document/clauses/rider01-art5-p2"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void 문서는_있지만_없는_조항이면_404_NOT_FOUND() {
        String documentId = uploadAndWaitReady();

        ResponseEntity<String> response = rest.getForEntity(
                url("/api/documents/" + documentId + "/clauses/no-such-clause"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
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

        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String b = rest.getForEntity(url("/api/documents/" + documentId), String.class).getBody();
            if (b != null && b.contains("\"status\":\"READY\"")) {
                return documentId;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("문서가 READY가 되지 않음: " + documentId);
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
