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

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.mock-documents=true(MOCK_DOCUMENTS 환경변수)일 때의 동작을 HTTP 레벨로 확인한다.
 * - PDF 형식·용량 검사(F-02)는 실제와 똑같이 한다.
 * - 글자 추출·구조 분석(F-04)만 건너뛰고, 업로드 직후 CHECKING이었다가 잠시 뒤
 *   명세서 예시와 같은 고정 READY 결과로 바뀐다.
 * 지연은 테스트가 느려지지 않도록 짧게(300ms) 줄여서 쓴다.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.mock-documents=true", "app.mock-documents-delay-ms=300"})
class MockDocumentsIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void PDF가_아닌_내용이어도_CHECKING으로_있다가_잠시_뒤_READY와_예시_특약목록을_돌려준다() {
        // 비동기 작업은 서버가 요청을 받는 즉시(= 응답을 보내기 전부터) 시작될 수 있으므로,
        // "업로드 요청을 보내기 직전"부터 시간을 재야 설정한 지연(300ms)을 안정적으로 확인할 수 있다.
        Instant start = Instant.now();

        // 확장자만 .pdf면 통과한다(F-02 형식 검사). 실제 내용은 분석하지 않으므로 아무 글자나 넣는다.
        ResponseEntity<String> uploadResponse = upload("whatever.pdf", "내용은 무엇이든 상관없다");
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(uploadResponse.getBody()).contains("\"status\":\"CHECKING\"");
        String documentId = extractDocumentId(uploadResponse.getBody());

        String finalBody = pollUntilNotChecking(documentId);
        // 설정한 지연(300ms)만큼은 CHECKING으로 머물러 있었어야 한다(= 업로드 응답 시점에
        // 바로 끝나버린 게 아니라는 뜻). 어느 정도 여유를 두고 확인한다.
        assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(200));

        assertThat(finalBody).contains("\"status\":\"READY\"");
        assertThat(finalBody).contains("\"riderId\":\"rider01\"");
        assertThat(finalBody).contains("\"pageCount\":212");
    }

    @Test
    void mock모드여도_PDF가_아니면_400_NOT_PDF를_바로_돌려준다() {
        ResponseEntity<String> response = upload("readme.txt", "hello");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"NOT_PDF\"");
    }

    private ResponseEntity<String> upload(String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        return rest.postForEntity(url("/api/documents"), request, String.class);
    }

    private String pollUntilNotChecking(String documentId) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> response = rest.getForEntity(url("/api/documents/" + documentId), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = response.getBody();
            if (body != null && !body.contains("\"status\":\"CHECKING\"")) {
                return body;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("5초 안에 READY가 되지 않음: " + documentId);
    }

    private String extractDocumentId(String json) {
        String marker = "\"documentId\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
