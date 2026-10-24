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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.mock-documents=true(MOCK_DOCUMENTS 환경변수)일 때, 어떤 내용을 올리든
 * 명세서 예시와 같은 고정 READY 결과가 바로 내려오는지 HTTP 레벨로 확인한다.
 * 프론트가 실제 약관 없이 화면을 만들 때 기대하는 동작이다.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.mock-documents=true")
class MockDocumentsIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void PDF가_아닌_내용을_올려도_곧바로_READY와_예시_특약목록을_돌려준다() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("이건 PDF가 전혀 아님".getBytes()) {
            @Override
            public String getFilename() {
                return "whatever.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> uploadResponse = rest.postForEntity(url("/api/documents"), request, String.class);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(uploadResponse.getBody()).contains("\"status\":\"CHECKING\"");
        String documentId = extractDocumentId(uploadResponse.getBody());

        ResponseEntity<String> statusResponse = rest.getForEntity(
                url("/api/documents/" + documentId), String.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).contains("\"status\":\"READY\"");
        assertThat(statusResponse.getBody()).contains("\"riderId\":\"rider01\"");
        assertThat(statusResponse.getBody()).contains("\"pageCount\":212");
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
