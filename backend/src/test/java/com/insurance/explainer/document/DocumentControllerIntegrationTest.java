package com.insurance.explainer.document;

import com.insurance.explainer.Application;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST/GET/DELETE /api/documents 가 실제로 엮여서 동작하는지(DI, HTTP 상태 코드,
 * NOT_FOUND 오류 형식, 비동기 처리) 확인하는 가벼운 통합 테스트. 조항 내용 자체는
 * {@link DocumentUploadServiceTest}에서 더 자세히 다룬다.
 * <p>
 * 여기서는 Spring이 실제로 {@code @Async} 프록시를 적용하므로, 업로드 직후에는
 * CHECKING이고 백그라운드 처리가 끝난 뒤에야 최종 상태가 되는 것까지 확인한다.
 * MOCK_DOCUMENTS는 꺼서(false) 실제 분석 경로를 검증한다.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.mock-documents=false")
class DocumentControllerIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void 없는_문서를_조회하면_NOT_FOUND_형식의_오류를_반환한다() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/documents/no-such-id"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void 텍스트_파일을_올리면_400_NOT_PDF를_반환한다() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("hello".getBytes()) {
            @Override
            public String getFilename() {
                return "readme.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = rest.postForEntity(url("/api/documents"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"NOT_PDF\"");
    }

    @Test
    void 업로드_후_조회하면_실제_구조분석_결과가_내려온다() throws IOException {
        // 조항 패턴(한글 "제N조")과 맞지 않는 글자라, 구조 분석 단계에서 거절(REJECTED
        // STRUCTURE_FAILED)되는 경로까지 실제 PDFBox 추출로 확인한다. "READY + 특약 목록"
        // 경로는 한글 폰트 임베딩 문제를 피해 DocumentUploadServiceTest에서 가짜
        // PageExtractor로 더 자세히 검증한다.
        byte[] pdfBytes = buildTextPdf("This PDF has real extractable text but no Korean article headings.");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "terms.pdf";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> uploadResponse = rest.postForEntity(url("/api/documents"), request, String.class);

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // 명세서대로 POST는 글자 추출·구조 분석을 기다리지 않고 곧장 CHECKING을 돌려준다.
        // (끝날 때까지 기다렸다면 이 응답 자체가 이미 REJECTED/READY였을 것이다.)
        assertThat(uploadResponse.getBody()).contains("\"status\":\"CHECKING\"");

        String documentId = extractDocumentId(uploadResponse.getBody());

        // 비동기 처리가 끝날 때까지 명세서의 권장 간격(2~3초)으로 조회를 반복한다.
        String statusBody = pollUntilNotChecking(documentId);
        assertThat(statusBody).contains("\"status\":\"REJECTED\"");
        assertThat(statusBody).contains("\"code\":\"STRUCTURE_FAILED\"");

        ResponseEntity<Void> deleteResponse = rest.exchange(
                url("/api/documents/" + documentId), HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String pollUntilNotChecking(String documentId) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> response = rest.getForEntity(url("/api/documents/" + documentId), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = response.getBody();
            if (body != null && !body.contains("\"status\":\"CHECKING\"")) {
                return body;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("10초 안에 비동기 처리가 끝나지 않음: " + documentId);
    }

    private byte[] buildTextPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
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
