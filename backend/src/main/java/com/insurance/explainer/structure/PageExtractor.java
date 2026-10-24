package com.insurance.explainer.structure;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * PDF(또는 그 밖의 입력)에서 쪽별 글자를 뽑아내는 역할의 최소 계약.
 * {@link PdfPageTextExtractor}가 실제(PDFBox) 구현이다. 테스트에서는 이 인터페이스를
 * 대체 구현(고정 텍스트 등)으로 바꿔 끼워 구조 분석 연결 로직을 PDF/폰트 문제와
 * 분리해 검증할 수 있다.
 */
@FunctionalInterface
public interface PageExtractor {
    List<PageText> extract(InputStream input) throws IOException;
}
