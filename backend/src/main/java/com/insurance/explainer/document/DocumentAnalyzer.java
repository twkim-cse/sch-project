package com.insurance.explainer.document;

import com.insurance.explainer.structure.DocumentStructureAnalyzer;
import com.insurance.explainer.structure.PageExtractor;
import com.insurance.explainer.structure.PageText;
import com.insurance.explainer.structure.RiderRange;
import com.insurance.explainer.structure.StructureAnalysisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 업로드된 PDF의 글자 추출·구조 분석(F-02/F-04)을 실제로 비동기로 진행하는
 * 작업자. {@link DocumentUploadService}와 별도 빈으로 둔 이유: 같은 빈 안에서
 * {@code this.메서드()}로 자기 자신을 호출하면 Spring의 {@code @Async} 프록시를
 * 거치지 않아 비동기로 동작하지 않는다(self-invocation 문제, 분석 쪽
 * {@code AnalysisRunner}와 같은 이유). 그래서 {@link DocumentUploadService#accept}가
 * 이 빈을 (다른 빈으로서) 호출하도록 분리했다.
 */
@Component
public class DocumentAnalyzer {

    private static final int DOCUMENT_RETENTION_HOURS = 24;

    private final PageExtractor pageExtractor;
    private final DocumentStructureAnalyzer structureAnalyzer;
    private final DocumentStore store;
    private final boolean mockDocuments;
    private final long mockDelayMs;

    public DocumentAnalyzer(PageExtractor pageExtractor,
                             DocumentStructureAnalyzer structureAnalyzer,
                             DocumentStore store,
                             @Value("${app.mock-documents:false}") boolean mockDocuments,
                             @Value("${app.mock-documents-delay-ms:2500}") long mockDelayMs) {
        this.pageExtractor = pageExtractor;
        this.structureAnalyzer = structureAnalyzer;
        this.store = store;
        this.mockDocuments = mockDocuments;
        this.mockDelayMs = mockDelayMs;
    }

    @Async
    public void analyzeAsync(String documentId, byte[] pdfBytes) {
        if (mockDocuments) {
            sleepQuietly(mockDelayMs);
            store.save(MockDocumentData.ready(documentId));
            return;
        }
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
                .filter(p -> p.text().strip().length() < DocumentUploadService.MIN_PAGE_TEXT_LENGTH)
                .count();
        return nearEmptyPages * 2 > pages.size();
    }

    private RiderInfo toRiderInfo(RiderRange rider) {
        return new RiderInfo(rider.riderId(), rider.name(), rider.pageStart());
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
