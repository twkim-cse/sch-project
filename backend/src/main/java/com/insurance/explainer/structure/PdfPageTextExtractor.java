package com.insurance.explainer.structure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * F-02 문서 읽기: 업로드된 PDF에서 쪽 번호를 유지하며 글자를 추출한다.
 * 구조 분석(F-04)의 입력인 {@link PageText} 목록을 만든다.
 */
@Component
public class PdfPageTextExtractor {

    public List<PageText> extract(InputStream pdfInputStream) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfInputStream.readAllBytes())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = stripper.getText(document);
                pages.add(new PageText(pageNumber, text));
            }
        }
        return pages;
    }
}
