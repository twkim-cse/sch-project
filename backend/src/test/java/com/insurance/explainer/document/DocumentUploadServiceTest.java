package com.insurance.explainer.document;

import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.DocumentStructureAnalyzer;
import com.insurance.explainer.structure.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-02(입력 검사) + F-04(구조 분석) 연결을 검증한다.
 * <p>
 * 이 테스트는 {@code new DocumentUploadService(...)}로 직접 만든 일반 객체를 쓰므로
 * Spring AOP 프록시가 없어 {@code @Async}가 적용되지 않는다. 즉 {@link DocumentUploadService#accept}
 * 호출 안에서 {@link DocumentUploadService#analyzeAsync}가 같은 스레드로 곧장 실행되고,
 * 돌아왔을 때 {@link DocumentStore}에는 이미 최종 상태(READY/REJECTED)가 들어 있다
 * (실제 비동기 동작, PENDING→READY 전환은 {@link DocumentControllerIntegrationTest}에서 확인한다).
 * <p>
 * 실제 PDFBox 추출이 필요 없는 케이스(조항 인식)는 가짜 {@link com.insurance.explainer.structure.PageExtractor}로
 * 한국어 조항 텍스트를 직접 공급해 검증하고, 입력 검사 자체(용량·형식·빈 글자)는
 * 실제로 만든 PDF 바이트로 검증한다.
 */
class DocumentUploadServiceTest {

    private final DocumentStructureAnalyzer analyzer = new DocumentStructureAnalyzer();

    @Test
    void PDF가_아니면_NOT_PDF_오류() {
        DocumentUploadService service = newService(unreachableExtractor(), false);
        MultipartFile file = new MockMultipartFile("file", "readme.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.accept(file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.code()).isEqualTo("NOT_PDF");
                    assertThat(apiException.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void 용량이_50MB를_넘으면_FILE_TOO_LARGE_오류() {
        DocumentUploadService service = newService(unreachableExtractor(), false);
        MultipartFile file = new OversizedFakeFile(DocumentUploadService.MAX_FILE_SIZE_BYTES + 1);

        assertThatThrownBy(() -> service.accept(file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.code()).isEqualTo("FILE_TOO_LARGE");
                    assertThat(apiException.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                });
    }

    @Test
    void 글자가_없는_스캔본_PDF는_NO_TEXT로_거절() throws IOException {
        DocumentStore store = new DocumentStore();
        DocumentUploadService service = newService(store, new com.insurance.explainer.structure.PdfPageTextExtractor(), false);
        byte[] blankPdf = buildBlankPdf();
        MultipartFile file = new MockMultipartFile("file", "scan.pdf", "application/pdf", blankPdf);

        DocumentRecord accepted = service.accept(file);
        DocumentRecord record = store.get(accepted.documentId());

        assertThat(record.status()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(record.rejectCode()).isEqualTo("NO_TEXT");
    }

    @Test
    void 글자가_거의_없는_쪽이_절반을_넘으면_NO_TEXT로_거절() {
        // 3쪽 중 2쪽이 거의 비어 있으면(절반 초과) 한 쪽에 충분한 글자가 있어도 스캔본으로 본다.
        List<PageText> mostlyBlankPages = List.of(
                new PageText(1, ""),
                new PageText(2, "제1조(목적) 이 약관은 보험계약의 내용을 규정합니다. 아주 충분히 긴 본문입니다."),
                new PageText(3, "  ")
        );
        DocumentStore store = new DocumentStore();
        DocumentUploadService service = newService(store, input -> mostlyBlankPages, false);
        MultipartFile file = new MockMultipartFile("file", "terms.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentRecord accepted = service.accept(file);
        DocumentRecord record = store.get(accepted.documentId());

        assertThat(record.status()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(record.rejectCode()).isEqualTo("NO_TEXT");
    }

    @Test
    void 조항_구조를_인식하지_못하면_STRUCTURE_FAILED로_거절() throws IOException {
        DocumentStore store = new DocumentStore();
        DocumentUploadService service = newService(store, new com.insurance.explainer.structure.PdfPageTextExtractor(), false);
        byte[] plainTextPdf = buildTextPdf(
                "This document has plenty of extractable text but none of it looks like a Korean insurance clause.");
        MultipartFile file = new MockMultipartFile("file", "not-terms.pdf", "application/pdf", plainTextPdf);

        DocumentRecord accepted = service.accept(file);
        DocumentRecord record = store.get(accepted.documentId());

        assertThat(record.status()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(record.rejectCode()).isEqualTo("STRUCTURE_FAILED");
    }

    @Test
    void 정상_약관이면_READY_상태로_특약목록과_쪽수를_채운다() {
        List<PageText> koreanPages = List.of(
                new PageText(1, "제1조(목적)\n이 약관은 보험계약의 내용을 규정합니다."),
                new PageText(2, "암진단 특별약관\n제5조(보험금 지급에 관한 세부규정)\n"
                        + "① 암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.")
        );
        DocumentStore store = new DocumentStore();
        DocumentUploadService service = newService(store, input -> koreanPages, false);
        MultipartFile file = new MockMultipartFile("file", "terms.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentRecord accepted = service.accept(file);
        assertThat(accepted.status()).isEqualTo(DocumentStatus.PENDING);

        DocumentRecord record = store.get(accepted.documentId());
        assertThat(record.status()).isEqualTo(DocumentStatus.READY);
        assertThat(record.pageCount()).isEqualTo(2);
        assertThat(record.riders()).extracting(RiderInfo::riderId).containsExactly("rider01");
        assertThat(record.riders()).extracting(RiderInfo::name).containsExactly("암진단 특별약관");
        assertThat(record.clauses()).hasSize(2);
        assertThat(record.expiresAt()).isNotNull();
    }

    @Test
    void MOCK_DOCUMENTS가_켜져있으면_글자추출_구조분석만_건너뛰고_고정_READY_결과를_돌려준다() {
        DocumentStore store = new DocumentStore();
        // 글자 추출(unreachableExtractor)은 호출되면 안 되지만, PDF 형식 검사는 통과해야 한다.
        DocumentUploadService service = newService(store, unreachableExtractor(), true);
        MultipartFile file = new MockMultipartFile("file", "whatever.pdf", "application/pdf",
                "내용은 무엇이든 상관없다, 어차피 분석하지 않으니까".getBytes());

        DocumentRecord accepted = service.accept(file);
        assertThat(accepted.status()).isEqualTo(DocumentStatus.PENDING);

        DocumentRecord record = store.get(accepted.documentId());
        assertThat(record.status()).isEqualTo(DocumentStatus.READY);
        assertThat(record.pageCount()).isEqualTo(212);
        assertThat(record.riders()).extracting(RiderInfo::riderId).containsExactly("rider01");
    }

    @Test
    void MOCK_DOCUMENTS가_켜져있어도_PDF_형식_검사는_실제와_똑같이_한다() {
        DocumentUploadService service = newService(unreachableExtractor(), true);
        MultipartFile file = new MockMultipartFile("file", "readme.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.accept(file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("NOT_PDF"));
    }

    @Test
    void MOCK_DOCUMENTS가_켜져있어도_용량_검사는_실제와_똑같이_한다() {
        DocumentUploadService service = newService(unreachableExtractor(), true);
        MultipartFile file = new OversizedFakeFile(DocumentUploadService.MAX_FILE_SIZE_BYTES + 1);

        assertThatThrownBy(() -> service.accept(file))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("FILE_TOO_LARGE"));
    }

    // ---- 테스트 보조 ----

    private DocumentUploadService newService(com.insurance.explainer.structure.PageExtractor extractor, boolean mock) {
        return newService(new DocumentStore(), extractor, mock);
    }

    private DocumentUploadService newService(DocumentStore store,
                                              com.insurance.explainer.structure.PageExtractor extractor,
                                              boolean mock) {
        // 지연은 0으로 둬서 단위 테스트가 느려지지 않게 한다. 실제 지연 동작은
        // MockDocumentsIntegrationTest에서 확인한다.
        return new DocumentUploadService(extractor, analyzer, store, mock, 0);
    }

    private com.insurance.explainer.structure.PageExtractor unreachableExtractor() {
        return input -> {
            throw new AssertionError("입력 검사에서 걸러져야 하므로 호출되면 안 됨");
        };
    }

    private byte[] buildBlankPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
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

    /** getSize()만 거짓으로 크게 돌려주는 가짜 파일. 실제로 큰 바이트 배열을 만들지 않는다. */
    private record OversizedFakeFile(long fakeSize) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return "huge.pdf";
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return fakeSize;
        }

        @Override
        public byte[] getBytes() {
            return new byte[0];
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException();
        }
    }
}
