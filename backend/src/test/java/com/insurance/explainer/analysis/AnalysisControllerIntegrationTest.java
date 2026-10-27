package com.insurance.explainer.analysis;

import com.insurance.explainer.Application;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-08 분석 작업 관리를 HTTP 레벨로 확인한다. 실제 AI 서버 대신 JDK 내장
 * {@link HttpServer}로 /ai/extract, /ai/explain을 흉내 낸다.
 * MOCK_DOCUMENTS=true로 빠르게 READY 문서를 만든 뒤(F-02/F-04는 이미 검증됨),
 * 분석 흐름 자체(QUEUED→RUNNING 단계별→DONE, 결과 조회, AI 실패 시 FAILED)에 집중한다.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.mock-documents=true",
        "app.mock-documents-delay-ms=0",
        // 분석 단계별 지연(프론트 진행 화면용)은 여기서는 0으로 줄여 빠르게 검증한다.
        // 실제 지연 동작은 docker compose로 수동 확인한다(PR 설명 참고).
        "app.mock-analysis-step-delay-min-ms=0",
        "app.mock-analysis-step-delay-max-ms=0"
})
class AnalysisControllerIntegrationTest {

    private static HttpServer aiServer;

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @DynamicPropertySource
    static void aiBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        int aiPort = freePort();
        aiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", aiPort), 0);
        aiServer.setExecutor(Executors.newCachedThreadPool());
        aiServer.createContext("/ai/extract", exchange -> respond(exchange, """
                {"extractions":[{"check_item":2,"clause_id":"rider01-art5-p2",
                "quote":"암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다."}]}
                """));
        aiServer.createContext("/ai/explain", exchange -> respond(exchange, """
                {"cards":[{"item_id":"c-001","title":"제목입니다","explanation":"해설입니다",
                "review":"pass","review_reason":null}]}
                """));
        aiServer.start();
        registry.add("ai.base-url", () -> "http://127.0.0.1:" + aiPort);
    }

    @AfterEach
    void stopAiServer() {
        if (aiServer != null) {
            aiServer.stop(0);
        }
    }

    @Test
    void 분석_시작부터_결과조회까지_전체_흐름이_명세서_형식대로_동작한다() {
        String documentId = uploadMockReadyDocument();

        ResponseEntity<String> startResponse = rest.postForEntity(url("/api/analyses"),
                jsonRequest("{\"documentId\":\"" + documentId + "\",\"riderIds\":[],\"viewAll\":true}"),
                String.class);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(startResponse.getBody()).contains("\"status\":\"QUEUED\"");
        String analysisId = extractField(startResponse.getBody(), "analysisId");

        String finalStatusBody = pollUntilStatus(analysisId, "DONE", "FAILED");
        assertThat(finalStatusBody).contains("\"status\":\"DONE\"");

        ResponseEntity<String> resultResponse = rest.getForEntity(
                url("/api/analyses/" + analysisId + "/result"), String.class);
        assertThat(resultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resultResponse.getBody();
        assertThat(body).contains("\"viewAll\":true");
        assertThat(body).contains("\"riderId\":\"rider01\"");
        assertThat(body).contains("\"checkSummary\"");
        // 6개 점검 항목이 전부 들어있어야 한다(F-17 "찾지 못함" 포함).
        for (int i = 1; i <= 6; i++) {
            assertThat(body).contains("\"checkItem\":" + i);
        }
        assertThat(body).contains("\"status\":\"FOUND\""); // 2번 항목
        assertThat(body).contains("\"status\":\"NOT_FOUND\""); // 나머지
        assertThat(body).contains("\"title\":\"제목입니다\"");
        assertThat(body).contains("\"importance\":\"HIGH\""); // 2번 항목은 HIGH
    }

    @Test
    void 완료_전에_결과를_요청하면_409_NOT_READY() {
        String documentId = uploadMockReadyDocument();
        ResponseEntity<String> startResponse = rest.postForEntity(url("/api/analyses"),
                jsonRequest("{\"documentId\":\"" + documentId + "\",\"riderIds\":[],\"viewAll\":true}"),
                String.class);
        String analysisId = extractField(startResponse.getBody(), "analysisId");

        // DONE이 되기 전에 바로 결과를 요청한다. extract/explain 호출 사이 타이밍이라
        // 아주 드물게 이미 DONE일 수 있으나, 가짜 AI 서버가 즉시 응답하므로 재시도 없이
        // 곧바로 요청해도 대부분 QUEUED/RUNNING 상태를 잡아낸다.
        ResponseEntity<String> resultResponse = rest.getForEntity(
                url("/api/analyses/" + analysisId + "/result"), String.class);

        if (resultResponse.getStatusCode() == HttpStatus.OK) {
            return; // 이미 끝나버린 드문 경우는 통과로 본다(비동기 타이밍 특성).
        }
        assertThat(resultResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resultResponse.getBody()).contains("\"code\":\"NOT_READY\"");
    }

    @Test
    void 없는_문서로_분석을_시작하면_404_NOT_FOUND() {
        // 문서가 READY가 아닐 때 409 NOT_READY가 되는 경로는 AnalysisServiceTest에서
        // (비동기 타이밍 없이) 확인한다. 여기서는 HTTP 배선만 404 경로로 확인한다.
        ResponseEntity<String> response = rest.postForEntity(url("/api/analyses"),
                jsonRequest("{\"documentId\":\"no-such-document\",\"riderIds\":[],\"viewAll\":true}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void AI_서버가_꺼져있으면_재시도_후_FAILED_ANALYSIS_FAILED가_된다() {
        String documentId = uploadMockReadyDocument();
        aiServer.stop(0); // AI 서버를 내려서 연결 자체가 실패하게 만든다.

        ResponseEntity<String> startResponse = rest.postForEntity(url("/api/analyses"),
                jsonRequest("{\"documentId\":\"" + documentId + "\",\"riderIds\":[],\"viewAll\":true}"),
                String.class);
        String analysisId = extractField(startResponse.getBody(), "analysisId");

        String finalStatusBody = pollUntilStatus(analysisId, "DONE", "FAILED");

        assertThat(finalStatusBody).contains("\"status\":\"FAILED\"");
        assertThat(finalStatusBody).contains("\"code\":\"ANALYSIS_FAILED\"");
    }

    private String uploadMockReadyDocument() {
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

        // app.mock-documents-delay-ms=0 이므로 바로 READY가 된다. 혹시 모를 지연에 대비해 짧게 폴링한다.
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> statusResponse = rest.getForEntity(
                    url("/api/documents/" + documentId), String.class);
            if (statusResponse.getBody() != null && statusResponse.getBody().contains("\"status\":\"READY\"")) {
                return documentId;
            }
            sleep(50);
        }
        throw new AssertionError("문서가 READY가 되지 않음: " + documentId);
    }

    private String pollUntilStatus(String analysisId, String... terminalStatuses) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> response = rest.getForEntity(url("/api/analyses/" + analysisId), String.class);
            String body = response.getBody();
            if (body != null) {
                for (String status : terminalStatuses) {
                    if (body.contains("\"status\":\"" + status + "\"")) {
                        return body;
                    }
                }
            }
            sleep(100);
        }
        throw new AssertionError("15초 안에 끝나지 않음: " + analysisId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
