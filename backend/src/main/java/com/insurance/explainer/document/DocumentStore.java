package com.insurance.explainer.document;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리 문서 저장소. 서버를 재시작하면 비워진다.
@Component
public class DocumentStore {

    private final Map<String, DocumentRecord> documents = new ConcurrentHashMap<>();

    public void save(DocumentRecord record) {
        documents.put(record.documentId(), record);
    }

    public DocumentRecord get(String documentId) {
        return documents.get(documentId);
    }

    public void delete(String documentId) {
        documents.remove(documentId);
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
