import { assertAnalysis, assertDocument, assertResult } from '../features/upload/uploadModel.js'

function isMissing(error) {
  return error.status === 404 || error.status === 410 || error.code === 'NOT_FOUND'
}

// The specification's analysis status has no expiresAt. Recheck the document
// instead of inventing a retention deadline or trusting stored result flags.
export function createSpringAnalysisBackend(api, getReference) {
  return {
    async getAnalysis(analysisId) {
      try {
        const analysis = assertAnalysis(await api.getAnalysis(analysisId), analysisId)
        if (analysis.status !== 'DONE') return analysis
        const reference = getReference()
        if (reference?.analysisId !== analysisId || !reference.documentId) return null
        const document = assertDocument(await api.getDocument(reference.documentId), reference.documentId)
        if (document.status !== 'READY') return null
        return { ...analysis, documentId: document.documentId, document, expiresAt: document.expiresAt, availabilityVerified: true }
      } catch (error) {
        if (isMissing(error)) return null
        throw error
      }
    },
    async getResult(analysisId) {
      try {
        return assertResult(await api.getResult(analysisId), analysisId)
      } catch (error) {
        if (isMissing(error)) return null
        throw error
      }
    },
  }
}
