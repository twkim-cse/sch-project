package com.insurance.explainer.document;

import com.insurance.explainer.mockapi.ApiException;
import com.insurance.explainer.structure.Clause;
import com.insurance.explainer.structure.DocumentStructureAnalyzer;
import com.insurance.explainer.structure.PageExtractor;
import com.insurance.explainer.structure.PageText;
import com.insurance.explainer.structure.RiderRange;
import com.insurance.explainer.structure.StructureAnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * F-02 입력 검사 + F-04 구조 분석 연결.
 * <p>
 * 업로드된 파일이 PDF인지, 50MB 이하인지 확인한 뒤 PDFBox로 쪽 번호를 유지하며
 * 글자를 추출하고, 글자가 거의 없으면 스캔본으로 보고 거절(NO_TEXT)한다.
 * 통과하면 {@link DocumentStructureAnalyzer}로 조항 구조를 분석해 특약 목록과
 * 쪽수를 채운 {@link DocumentRecord}를 만든다. 구조를 인식하지 못하면 거절(STRUCTURE_FAILED)한다.
 * <p>
 * F-03(문서 종류 판별)은 아직 AI가 가짜 응답이라, 지금은 모든 PDF를 약관(TERMS)으로
 * 보고 그대로 구조 분석을 진행한다. 상품명·판은 채우지 않는다(F-03 연동 후 채워짐).
 */
@Service
public class DocumentUploadService {

    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB
    // 이보다 적은 글자만 추출되면 스캔본(글자 없는 PDF)으로 본다.
    public static final int MIN_EXTRACTED_TEXT_LENGTH = 30;
    private static final int DOCUMENT_RETENTION_HOURS = 24;

    private final PageExtractor pageExtractor;
    private final DocumentStructureAnalyzer structureAnalyzer;

    public DocumentUploadService(PageExtractor pageExtractor, DocumentStructureAnalyzer structureAnalyzer) {
        this.pageExtractor = pageExtractor;
        this.structureAnalyzer = structureAnalyzer;
    }

    public DocumentRecord process(MultipartFile file) {
        validate(file);

        String documentId = UUID.randomUUID().toString();
        List<PageText> pages = extract(file);

        int totalTextLength = pages.stream().mapToInt(p -> p.text().strip().length()).sum();
        if (totalTextLength < MIN_EXTRACTED_TEXT_LENGTH) {
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

    private List<PageText> extract(MultipartFile file) {
        try {
            return pageExtractor.extract(file.getInputStream());
        } catch (IOException | RuntimeException e) {
            // 확장자는 .pdf여도 내용이 깨졌거나 PDF가 아니면 여기서 걸러진다.
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PDF", "PDF 파일만 올릴 수 있습니다.");
        }
    }

    private RiderInfo toRiderInfo(RiderRange rider) {
        return new RiderInfo(rider.riderId(), rider.name(), rider.pageStart());
    }
}
