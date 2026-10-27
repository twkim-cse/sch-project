package com.insurance.explainer.document;

import com.insurance.explainer.mockapi.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * F-02 입력 검사 접수.
 * <p>
 * 업로드된 파일이 PDF인지, 50MB 이하인지는 {@code MOCK_DOCUMENTS} 여부와 상관없이
 * 요청을 받는 즉시(동기) 똑같이 확인한다. 통과하면 명세서대로 곧바로 202 {@code CHECKING}을
 * 돌려주고, 나머지(PDFBox로 쪽 번호를 유지하며 글자 추출 → 구조 분석, F-04)는
 * {@link DocumentAnalyzer}가 비동기로 진행한다(진행 중에는 조회(GET)가 CHECKING을 돌려준다).
 * 실행을 별도 빈으로 분리한 이유는 {@link DocumentAnalyzer}의 클래스 설명을 참고
 * (self-invocation 문제로 같은 빈 안에서는 {@code @Async}가 걸리지 않는다).
 * <p>
 * {@code MOCK_DOCUMENTS=true}(환경변수, application.yml의 {@code app.mock-documents})
 * 이면 PDF 형식·용량 검사는 그대로 하되, 글자 추출과 구조 분석만 건너뛰고 명세서
 * 예시와 같은 모양의 고정 READY 결과를 비동기로(= CHECKING으로 있다가 잠시 뒤 READY로)
 * 돌려준다. 실제 약관 없이 프론트 화면을 만들어야 할 때 쓴다.
 */
@Service
public class DocumentUploadService {

    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB
    // 쪽 하나의 글자 수가 이보다 적으면 "글자가 거의 없는 쪽"으로 본다.
    public static final int MIN_PAGE_TEXT_LENGTH = 20;

    private final DocumentStore store;
    private final DocumentAnalyzer documentAnalyzer;

    public DocumentUploadService(DocumentStore store, DocumentAnalyzer documentAnalyzer) {
        this.store = store;
        this.documentAnalyzer = documentAnalyzer;
    }

    /**
     * 요청 스레드에서 호출된다. PDF 형식·용량은 MOCK_DOCUMENTS와 상관없이 항상 바로
     * 확인하고, 통과하면 PENDING 상태로 저장한 뒤 나머지는 {@link DocumentAnalyzer}에 맡긴다.
     */
    public DocumentRecord accept(MultipartFile file) {
        validate(file);
        byte[] pdfBytes = readBytes(file);

        String documentId = UUID.randomUUID().toString();
        DocumentRecord pending = DocumentRecord.pending(documentId);
        store.save(pending);
        documentAnalyzer.analyzeAsync(documentId, pdfBytes);
        return pending;
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
}
