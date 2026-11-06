// Test-only fixtures. This module is never imported by production pages.
export const readyDocument = {
  documentId: 'test-document', status: 'READY', productName: '테스트 전용 보험상품',
  versionLabel: '테스트 개정판', pageCount: 212, expiresAt: '2099-01-01T00:00:00+09:00',
  riders: [
    { riderId: 'test-rider-1', name: '테스트 진단 특별약관', pageStart: 85 },
    { riderId: 'test-rider-2', name: '테스트 수술 특별약관', pageStart: 120 },
  ],
}

export const completedResult = {
  analysisId: 'test-analysis',
  document: { productName: readyDocument.productName, versionLabel: readyDocument.versionLabel, pageCount: readyDocument.pageCount },
  viewAll: false, selectedRiders: [readyDocument.riders[0]],
  checkSummary: Array.from({ length: 6 }, (_, index) => ({
    checkItem: index + 1, name: `테스트 점검 ${index + 1}`, status: index < 2 ? 'FOUND' : 'NOT_FOUND', cardCount: index < 2 ? 1 : 0,
  })),
  groups: [
    { scope: 'MAIN', riderId: null, name: '공통 (주계약)', cards: [{
      cardId: 'test-card-1', checkItem: 1, importance: 'HIGH', display: 'FULL',
      title: '테스트 보험금 지급 제한', explanation: '검증된 테스트 해설입니다.', quote: '테스트 원문 인용입니다.',
      source: { section: '주계약', article: '제12조', paragraph: '제2항', page: 14 }, clauseId: 'test-clause-1', fromTable: false,
    }] },
    { scope: 'RIDER', riderId: 'test-rider-1', name: '테스트 진단 특별약관', cards: [{
      cardId: 'test-card-2', checkItem: 2, importance: 'NORMAL', display: 'QUOTE_ONLY',
      title: '표시 금지 테스트 제목', explanation: '표시 금지 테스트 해설', quote: '특약 테스트 인용입니다.',
      source: { section: '테스트 진단 특별약관', article: '제5조', page: 87 }, clauseId: 'test-clause-2', fromTable: true,
    }] },
  ],
}
