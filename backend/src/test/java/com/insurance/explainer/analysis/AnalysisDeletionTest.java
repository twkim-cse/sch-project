package com.insurance.explainer.analysis;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDeletionTest {
    @Test void deleted_analysis_cannot_be_resurrected_by_late_worker() {
        AnalysisStore store=new AnalysisStore();
        var record=AnalysisRecord.queued("id","document",true,null,null,1,List.of(),List.of());
        AtomicBoolean called=new AtomicBoolean();store.onDelete(id -> called.set(true));
        store.save(record);store.delete("id");store.save(record.failed("ANALYSIS_FAILED","safe"));
        assertThat(store.get("id")).isNull();assertThat(called).isTrue();
    }
}
