package com.insurance.explainer.mockapi.documents;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜(mock) 문서 저장소 한 건. 실제 분석은 하지 않고, 명세서 4장의 예시 값을
 * 고정으로 돌려준다. 조회(GET) 호출 횟수만 세어 CHECKING → READY 전환을 흉내 낸다.
 */
public class DocumentRecord {

    // 이 횟수만큼 상태를 조회하면 READY로 바뀐다.
    public static final int READY_AFTER_CALLS = 3;

    private final String documentId;
    private final String productName;
    private final String versionLabel;
    private final int pageCount;
    private final List<RiderInfo> riders;
    private final OffsetDateTime expiresAt;
    private final AtomicInteger checkCallCount = new AtomicInteger(0);

    public DocumentRecord(String documentId, String productName, String versionLabel,
                           int pageCount, List<RiderInfo> riders, OffsetDateTime expiresAt) {
        this.documentId = documentId;
        this.productName = productName;
        this.versionLabel = versionLabel;
        this.pageCount = pageCount;
        this.riders = riders;
        this.expiresAt = expiresAt;
    }

    /** 조회할 때마다 호출. 아직 READY_AFTER_CALLS에 못 미치면 true(CHECKING 유지). */
    public boolean isStillChecking() {
        return checkCallCount.incrementAndGet() < READY_AFTER_CALLS;
    }

    public String documentId() {
        return documentId;
    }

    public String productName() {
        return productName;
    }

    public String versionLabel() {
        return versionLabel;
    }

    public int pageCount() {
        return pageCount;
    }

    public List<RiderInfo> riders() {
        return riders;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }
}
