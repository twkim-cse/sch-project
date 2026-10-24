package com.insurance.explainer.document;

import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.DocumentStructureAnalyzer;
import com.insurance.explainer.structure.PageExtractor;
import com.insurance.explainer.structure.PageText;
import com.insurance.explainer.structure.RiderRange;
import com.insurance.explainer.structure.StructureAnalysisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * F-02 입력 검사 + F-04 구조 분석 연결.
 * <p>
 * 업로드된 파일이 PDF인지, 50MB 이하인지는 요청을 받는 즉시(동기) 확인한다.
 * 통과하면 명세서대로 곧바로 202 {@code CHECKING}을 돌려주고, PDFBox로 쪽 번호를
 * 유지하며 글자를 추출한 뒤 {@link DocumentStructureAnalyzer}로 조항 구조를 분석하는
 * 작업은 비동기로 진행한다(진행 중에는 조회(GET)가 CHECKING을 돌려준다).
 * 글자가 거의 없는 쪽이 전체 쪽의 절반을 넘으면 스캔본으로 보고 거절(NO_TEXT)하고,
 * 조항 구조를 인식하지 못하면 거절(STRUCTURE_FAILED)한다.
 * <p>
 * F-03(문서 종류 판별)은 아직 AI가 가짜 응답이라, 지금은 모든 PDF를 약관(TERMS)으로
 * 보고 그대로 구조 분석을 진행한다. 상품명·판은 채우지 않는다(F-03 연동 후 채워짐).
 * <p>
 * {@code MOCK_DOCUMENTS=true}(환경변수, application.yml의 {@code app.mock-documents})
 * 이면 위 과정을 전부 건너뛰고 명세서 예시와 같은 모양의 고정 READY 결과를 바로 돌려준다.
 * 실제 약관 없이 프론트 화면을 만들어야 할 때 쓴다.
 */
@Service
public class DocumentUploadService {

    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB
    // 쪽 하나의 글자 수가 이보다 적으면 "글자가 거의 없는 쪽"으로 본다.
    public static final int MIN_PAGE_TEXT_LENGTH = 20;
    private static final int DOCUMENT_RETENTION_HOURS = 24;

    private final PageExtractor pageExtractor;
    private final DocumentStructureAnalyzer structureAnalyzer;
    private final DocumentStore store;
    private final boolean mockDocuments;

    public DocumentUploadService(PageExtractor pageExtractor,
                                  DocumentStructureAnalyzer structureAnalyzer,
                                  DocumentStore store,
                                  @Value("${app.mock-documents:false}") boolean mockDocuments) {
        this.pageExtractor = pageExtractor;
        this.structureAnalyzer = structureAnalyzer;
        this.store = store;
        this.mockDocuments = mockDocuments;
    }

    /**
     * 요청 스레드에서 호출된다. PDF 형식·용량만 바로 확인하고, 통과하면 PENDING 상태로
     * 저장한 뒤 글자 추출·구조 분석은 {@link #analyzeAsync}에 맡긴다.
     */
    public DocumentRecord accept(MultipartFile file) {
        String documentId = UUID.randomUUID().toString();

        if (mockDocuments) {
            DocumentRecord ready = MockDocumentData.ready(documentId);
            store.save(ready);
            return ready;
        }

        validate(file);
        byte[] pdfBytes = readBytes(file);

        DocumentRecord pending = DocumentRecord.pending(documentId);
        store.save(pending);
        analyzeAsync(documentId, pdfBytes);
        return pending;
    }

    @Async
    public void analyzeAsync(String documentId, byte[] pdfBytes) {
        store.save(analyze(documentId, pdfBytes));
    }

    private DocumentRecord analyze(String documentId, byte[] pdfBytes) {
        List<PageText> pages;
        try {
            pages = pageExtractor.extract(new ByteArrayInputStream(pdfBytes));
        } catch (IOException | RuntimeException e) {
            return DocumentRecord.rejected(documentId, "NO_TEXT", "글자를 읽을 수 없는 PDF입니다.");
        }

        if (isScannedDocument(pages)) {
            return DocumentRecord.rejected(documentId, "NO_TEXT", "글자를 읽을 수 없는 PDF입니다.");
        }

        try {
            DocumentStructureAnalyzer.StructureAnalysisResult result = structureAnalyzer.analyze(pages);
            List<RiderInfo> riders = result.riders().stream()
                    .map(this::toRiderInfo)
                    .toList();
            OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.ofHours(9))
                    .plusHours(DOCUMENT_RETENTION_HOURS);
            return DocumentRecord.ready(documentId, pages.size(), riders, result.clauses(), expiresAt);
        } catch (StructureAnalysisException e) {
            return DocumentRecord.rejected(documentId, "STRUCTURE_FAILED", "이 약관은 아직 지원하지 않습니다.");
        }
    }

    /** 글자가 거의 없는 쪽(F-02)이 전체 쪽의 절반을 넘으면 스캔본으로 본다. */
    private boolean isScannedDocument(List<PageText> pages) {
        if (pages.isEmpty()) {
            return true;
        }
        long nearEmptyPages = pages.stream()
                .filter(p -> p.text().strip().length() < MIN_PAGE_TEXT_LENGTH)
                .count();
        return nearEmptyPages * 2 > pages.size();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PDF", "PDF 파일만 올릴 수 있습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "파일이 너무 큽니다. 50MB 이하의 PDF를 올려 주세요.");
        }
        boolean looksLikePdf = "application/pdf".equals(file.getContentType())
                || (file.getOriginalFilename() != null
                    && file.getOriginalFilename().toLowerCase().endsWith(".pdf"));
        if (!looksLikePdf) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PDF", "PDF 파일만 올릴 수 있습니다.");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PDF", "PDF 파일만 올릴 수 있습니다.");
        }
    }

    private RiderInfo toRiderInfo(RiderRange rider) {
        return new RiderInfo(rider.riderId(), rider.name(), rider.pageStart());
    }
}
