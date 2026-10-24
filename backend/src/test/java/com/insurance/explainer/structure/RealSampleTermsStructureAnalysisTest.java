package com.insurance.explainer.structure;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docs/samples/ 에 실제 약관 PDF를 넣으면, PDFBox로 쪽별 글자를 읽어
 * DocumentStructureAnalyzer를 돌려보는 확인용 테스트.
 * <p>
 * 지금은 샘플 PDF가 없으므로 {@code assumeTrue}로 건너뛴다(실패가 아니다).
 * docs/samples/*.pdf 를 추가하면 자동으로 실행되며, 특약 목록과 조항 목록
 * (clause_id, 쪽, 앞 50자)을 표준 출력으로 보여준다.
 */
class RealSampleTermsStructureAnalysisTest {

    private static final Path SAMPLES_DIR = Path.of("..", "docs", "samples");

    private final PdfPageTextExtractor extractor = new PdfPageTextExtractor();
    private final DocumentStructureAnalyzer analyzer = new DocumentStructureAnalyzer();

    @TestFactory
    Stream<DynamicTest> 샘플_PDF들의_구조_분석_결과를_출력한다() throws IOException {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            assumeTrue(false, "docs/samples/ 폴더가 없어 건너뜀");
        }
        List<Path> pdfFiles;
        try (Stream<Path> stream = Files.list(SAMPLES_DIR)) {
            pdfFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        }
        assumeTrue(!pdfFiles.isEmpty(), "docs/samples/ 에 PDF가 없어 건너뜀");

        return pdfFiles.stream().map(pdf -> DynamicTest.dynamicTest(pdf.getFileName().toString(), () -> {
            List<PageText> pages;
            try (InputStream in = Files.newInputStream(pdf)) {
                pages = extractor.extract(in);
            }

            var result = analyzer.analyze(pages);

            System.out.println("==== " + pdf.getFileName() + " ====");
            System.out.println("특약 수: " + result.riders().size());
            for (RiderRange rider : result.riders()) {
                System.out.println("  - " + rider.riderId() + ": " + rider.name() + " (p." + rider.pageStart() + ")");
            }
            System.out.println("조항 수: " + result.clauses().size());
            for (Clause c : result.clauses()) {
                String preview = c.text().length() > 50 ? c.text().substring(0, 50) : c.text();
                System.out.printf("  - %-20s p.%-4d %s%n", c.clauseId(), c.page(), preview);
            }

            assertThat(result.clauses()).isNotEmpty();
        }));
    }
}
