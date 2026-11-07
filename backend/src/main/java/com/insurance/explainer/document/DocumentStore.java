package com.insurance.explainer.document;

import org.springframework.stereotype.Component;
import com.insurance.explainer.persistence.LocalStateFiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Durable local storage in the configured private directory; no-arg fixtures stay in memory.
@Component
@org.springframework.context.annotation.DependsOn("localStateLease")
public class DocumentStore {

    private final Map<String, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final LocalStateFiles files;
    private final Map<String,Long> deletedAt=new ConcurrentHashMap<>();

    public DocumentStore() {this("");}
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentStore(@org.springframework.beans.factory.annotation.Value("${ai.checkpoint-dir:}") String directory) {
        files=LocalStateFiles.optional(directory,"documents");
        if(files!=null)files.all().forEach((id,node)->{
            DocumentRecord record=files.value(node,DocumentRecord.class);
            if(!id.equals(record.documentId()))throw new LocalStateFiles.StorageFailure();
            if(record.status()==DocumentStatus.PENDING)
                record=record.withRejected("UPLOAD_INTERRUPTED","서버 재시작 전에 PDF 확인이 끝나지 않았습니다. 다시 업로드해 주세요.");
            documents.put(id,record);
        });
    }

    public void save(DocumentRecord record) {
        deletedAt.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis()-java.time.Duration.ofHours(24).toMillis());
        documents.compute(record.documentId(),(id,old)->{
            if(deletedAt.containsKey(id))return null;
            if(files!=null)files.write(id,files.tree(record));return record;
        });
    }

    public DocumentRecord get(String documentId) {
        return documents.get(documentId);
    }

    public void delete(String documentId) {
        deletedAt.put(documentId,System.currentTimeMillis());
        documents.compute(documentId,(id,old)->{if(files!=null)files.delete(id);return null;});
    }

    public boolean exists(String documentId) {
        return documents.containsKey(documentId);
    }

    /** F-21: 보관 기한(expiresAt)이 now 이전으로 지난 문서의 ID 목록. */
    public List<String> findExpiredIds(OffsetDateTime now) {
        return documents.values().stream()
                .filter(d -> d.expiresAt() != null && !d.expiresAt().isAfter(now))
                .map(DocumentRecord::documentId)
                .toList();
    }
}
