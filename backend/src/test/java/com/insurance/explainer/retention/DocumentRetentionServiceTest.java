package com.insurance.explainer.retention;

import com.insurance.explainer.analysis.AnalysisRecord;
import com.insurance.explainer.analysis.AnalysisStore;
import com.insurance.explainer.document.DocumentRecord;
import com.insurance.explainer.document.DocumentStore;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-21 자동 삭제의 핵심 로직(DocumentRetentionService)을 검증한다. */
class DocumentRetentionServiceTest {

    private final DocumentStore documentStore = new DocumentStore();
    private final AnalysisStore analysisStore = new AnalysisStore();
    private final DocumentRetentionService service = new DocumentRetentionService(documentStore, analysisStore);

    @Test
    void 즉시_삭제하면_문서와_그_문서의_분석결과가_모두_지워진다() {
        documentStore.save(DocumentRecord.pending("doc-1", OffsetDateTime.now().plusHours(24))
                .withReady(1, List.of(), List.of()));
        analysisStore.save(AnalysisRecord.queued("analysis-1", "doc-1", false, null, null, 1, List.of(), List.of()));
        analysisStore.save(AnalysisRecord.queued("analysis-2", "doc-1", false, null, null, 1, List.of(), List.of()));
        analysisStore.save(AnalysisRecord.queued("analysis-other", "doc-2", false, null, null, 1, List.of(), List.of()));

        service.deleteDocumentAndAnalyses("doc-1");

        assertThat(documentStore.get("doc-1")).isNull();
        assertThat(analysisStore.get("analysis-1")).isNull();
        assertThat(analysisStore.get("analysis-2")).isNull();
        assertThat(analysisStore.get("analysis-other")).isNotNull(); // 다른 문서의 분석은 그대로
    }

    @Test
    void 보관기한이_지난_문서만_찾아서_분석결과와_함께_지운다() {
        OffsetDateTime now = OffsetDateTime.now();
        documentStore.save(DocumentRecord.pending("expired", now.minusMinutes(1)).withReady(1, List.of(), List.of()));
        documentStore.save(DocumentRecord.pending("still-valid", now.plusHours(1)).withReady(1, List.of(), List.of()));
        documentStore.save(DocumentRecord.pending("pending-no-deadline", null)); // expiresAt=null → 대상 아님
        analysisStore.save(AnalysisRecord.queued("a-expired", "expired", false, null, null, 1, List.of(), List.of()));
        analysisStore.save(AnalysisRecord.queued("a-valid", "still-valid", false, null, null, 1, List.of(), List.of()));

        service.purgeExpired();

        assertThat(documentStore.get("expired")).isNull();
        assertThat(analysisStore.get("a-expired")).isNull();
        assertThat(documentStore.get("still-valid")).isNotNull();
        assertThat(analysisStore.get("a-valid")).isNotNull();
        assertThat(documentStore.get("pending-no-deadline")).isNotNull();
    }
}
