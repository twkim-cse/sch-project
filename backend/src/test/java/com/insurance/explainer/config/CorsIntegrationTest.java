package com.insurance.explainer.config;

import com.insurance.explainer.Application;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.mock-documents=false")
class CorsIntegrationTest {

    private static final String RAILWAY_ORIGIN =
            "https://independent-encouragement-production-53ba.up.railway.app";
    private static final String LOCAL_ORIGIN = "http://localhost:5173";
    private static final List<HttpMethod> METHODS = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @BeforeEach
    void configureHttpClient() {
        // Send Origin over real HTTP without HttpURLConnection's restricted-header filtering.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    static Stream<Arguments> allowedPreflights() {
        return Stream.of(RAILWAY_ORIGIN, LOCAL_ORIGIN)
                .flatMap(origin -> METHODS.stream().map(method -> Arguments.of(origin, method)));
    }

    @ParameterizedTest
    @MethodSource("allowedPreflights")
    void allowsRequiredMethodsAndHeaders(String origin, HttpMethod method) {
        ResponseEntity<String> response = preflight("/api/documents", origin, method,
                "accept", "content-type", "authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
        assertThat(response.getHeaders().getAccessControlAllowMethods()).containsAll(METHODS);
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .contains("accept", "content-type", "authorization");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    void appliesToOtherApiEndpoints() {
        ResponseEntity<String> response = preflight("/api/analyses", RAILWAY_ORIGIN,
                HttpMethod.POST, "content-type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(RAILWAY_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {RAILWAY_ORIGIN, LOCAL_ORIGIN})
    void includesCorsHeaderOnActualPdfUpload(String origin) throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            pdf = out.toByteArray();
        }

        ResponseEntity<String> response = upload(origin, "terms.pdf", pdf);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
        assertThat(response.getBody()).contains("\"documentId\":", "\"status\":\"CHECKING\"");
    }

    @Test
    void includesCorsHeaderOnUploadValidationError() {
        ResponseEntity<String> response = upload(RAILWAY_ORIGIN, "readme.txt", new byte[]{1});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(RAILWAY_ORIGIN);
        assertThat(response.getBody()).contains("\"code\":\"NOT_PDF\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "http://localhost:5174",
            "https://independent-encouragement-production-53ba.up.railway.app.evil.example"})
    void rejectsUnapprovedOrigins(String origin) {
        ResponseEntity<String> preflight = preflight("/api/documents", origin, HttpMethod.POST, "content-type");
        ResponseEntity<String> upload = upload(origin, "terms.pdf", new byte[]{1});

        assertThat(preflight.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void rejectsUnapprovedMethodsAndHeaders() {
        ResponseEntity<String> method = preflight("/api/documents", RAILWAY_ORIGIN, HttpMethod.TRACE);
        ResponseEntity<String> header = preflight("/api/documents", RAILWAY_ORIGIN,
                HttpMethod.POST, "x-unapproved-header");

        assertThat(method.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(method.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(header.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(header.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void preservesRequestsWithoutOrigin() {
        ResponseEntity<String> response = upload(null, "readme.txt", new byte[]{1});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getBody()).contains("\"code\":\"NOT_PDF\"");
    }

    private ResponseEntity<String> preflight(String path, String origin, HttpMethod method, String... headers) {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setOrigin(origin);
        requestHeaders.setAccessControlRequestMethod(method);
        requestHeaders.setAccessControlRequestHeaders(List.of(headers));
        return rest.exchange(url(path), HttpMethod.OPTIONS, new HttpEntity<>(requestHeaders), String.class);
    }

    private ResponseEntity<String> upload(String origin, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return rest.postForEntity(url("/api/documents"), new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
