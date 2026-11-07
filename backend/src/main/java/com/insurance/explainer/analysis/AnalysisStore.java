package com.insurance.explainer.analysis;

import org.springframework.stereotype.Component;
import com.insurance.explainer.persistence.LocalStateFiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Private atomic snapshots when configured; no-arg offline fixtures stay in memory.
@Component
@org.springframework.context.annotation.DependsOn("localStateLease")
public class AnalysisStore {

    private final Map<String, AnalysisRecord> analyses = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<String> deletionHook = id -> { };
    private final Map<String, Long> deletedAt = new ConcurrentHashMap<>();
    private final LocalStateFiles files;

    public AnalysisStore(){this("");}
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisStore(@org.springframework.beans.factory.annotation.Value("${ai.checkpoint-dir:}") String directory) {
        files=LocalStateFiles.optional(directory,"analyses");
        if(files!=null)files.all().forEach((id,node)->{
            AnalysisRecord record=files.value(node,AnalysisRecord.class);
            if(!id.equals(record.analysisId()))throw new LocalStateFiles.StorageFailure();
            if(record.status()==AnalysisStatus.RUNNING || record.status()==AnalysisStatus.QUEUED)
                record=record.failed("ANALYSIS_INTERRUPTED","서버 재시작으로 중단됐습니다. 체크포인트와 미처리 범위를 확인한 뒤 재개해 주세요.");
            analyses.put(id,record);
        });
    }

    public void onDelete(java.util.function.Consumer<String> hook) {
        this.deletionHook = hook;
    }

    public void save(AnalysisRecord record) {
        long cutoff = System.currentTimeMillis() - java.time.Duration.ofHours(24).toMillis();
        deletedAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        analyses.compute(record.analysisId(), (id, old) -> {
            if(deletedAt.containsKey(id))return null;
            if(files!=null)files.write(id,files.tree(record));
            return record;
        });
    }

    public AnalysisRecord get(String analysisId) {
        return analyses.get(analysisId);
    }

    /** Last resort when disk writes fail: never leave the live job RUNNING. */
    void markStorageFailure(AnalysisRecord record) {
        analyses.computeIfPresent(record.analysisId(), (id, old) -> deletedAt.containsKey(id) ? null
                : record.failed("LOCAL_CHECKPOINT_STORAGE_ERROR", "체크포인트 저장에 실패했습니다. 저장 공간과 권한을 확인해 주세요."));
    }

    public void delete(String analysisId) {
        deletedAt.put(analysisId, System.currentTimeMillis());
        deletionHook.accept(analysisId);
        analyses.compute(analysisId,(id,old)->{if(files!=null)files.delete(id);return null;});
    }

    /** F-21: 문서가 삭제(즉시 또는 보관 기한 만료)될 때 그 문서로 만든 분석도 함께 지우기 위함. */
    public List<String> findIdsByDocumentId(String documentId) {
        return analyses.values().stream()
                .filter(a -> documentId.equals(a.documentId()))
                .map(AnalysisRecord::analysisId)
                .toList();
    }
}
