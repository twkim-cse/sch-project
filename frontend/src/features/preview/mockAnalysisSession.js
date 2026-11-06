import { ApiError } from '../../api/springApi.js'
import { createResultsPreview, previewDocument } from '../results/resultsPreview.js'

// This module is reached only through the DEV-only lazy provider. There is no
// fetch implementation or connection to a real API in this session.
export function createMockAnalysisSession() {
  let snapshot = createResultsPreview()
  const listeners = new Set()
  function publish(next) {
    snapshot = next
    for (const listener of listeners) listener()
  }
  const missing = () => new ApiError('NOT_FOUND', '미리보기 데이터가 없습니다. 개발자 메뉴에서 분석 결과를 다시 열 수 있습니다.')
  return {
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    getSnapshot: () => snapshot,
    restore(options) { publish(createResultsPreview(options)) },
    clear() { publish({ bundle: null, api: null }) },
    service: { async getCompletedAnalysis() { return snapshot.bundle } },
    api: {
      async getDocument() { return previewDocument },
      async getAnalysis() { return snapshot.bundle?.analysis || { analysisId: 'preview-analysis', status: 'RUNNING' } },
      async getResult() { if (!snapshot.bundle) throw missing(); return snapshot.bundle.result },
      async getClause(documentId, clauseId, options) {
        if (!snapshot.api) throw missing()
        return snapshot.api.getClause(documentId, clauseId, options)
      },
      async deleteDocument(documentId) {
        if (documentId !== 'preview-document') throw missing()
        publish({ bundle: null, api: null })
        return null
      },
      // Accidental use of the live workflow still cannot upload anything.
      async uploadDocument() { throw new ApiError('PREVIEW_ONLY', '샘플 약관으로 테스트 버튼을 이용해 주세요. 서버 업로드는 실행되지 않습니다.') },
      async startAnalysis() { throw new ApiError('PREVIEW_ONLY', '개발용 수동 분석 진행을 이용해 주세요.') },
    },
  }
}
