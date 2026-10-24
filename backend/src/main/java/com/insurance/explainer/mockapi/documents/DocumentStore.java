package com.insurance.explainer.mockapi.documents;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 가짜 API용 인메모리 저장소. 서버를 재시작하면 비워진다.
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
}
