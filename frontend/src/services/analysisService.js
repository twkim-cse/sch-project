const ANALYSIS_ID_KEY = 'deulda.analysisId'
const ANALYSIS_REFERENCE_KEY = 'deulda.analysisReference'

function normalizeAnalysisId(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) {
    return String(value)
  }
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

export function readCurrentAnalysisId() {
  try {
    return normalizeAnalysisId(window.sessionStorage.getItem(ANALYSIS_ID_KEY))
  } catch {
    return null
  }
}

// Call with the analysisId returned by Spring after a future upload succeeds.
// Store only the reference; status and results must always be revalidated.
export function rememberAnalysisId(analysisId, reference = {}) {
  const id = normalizeAnalysisId(analysisId)
  if (!id) return false
  try {
    window.sessionStorage.setItem(ANALYSIS_ID_KEY, id)
    if (reference.documentId) {
      window.sessionStorage.setItem(ANALYSIS_REFERENCE_KEY, JSON.stringify({
        analysisId: id,
        documentId: reference.documentId,
        expiresAt: reference.expiresAt ?? null,
      }))
    } else {
      window.sessionStorage.removeItem(ANALYSIS_REFERENCE_KEY)
    }
    return true
  } catch {
    return false
  }
}

export function readAnalysisReference() {
  const analysisId = readCurrentAnalysisId()
  if (!analysisId) return null
  try {
    const reference = JSON.parse(window.sessionStorage.getItem(ANALYSIS_REFERENCE_KEY))
    if (reference?.analysisId === analysisId && reference.documentId) return reference
  } catch { /* An invalid stored reference is not a completed result. */ }
  return { analysisId }
}

// Only forget the deleted document; an in-flight deletion must not clear a
// newer analysis that the user has started elsewhere in the app.
export function forgetAnalysisReference(analysisId, documentId) {
  try {
    const current = readAnalysisReference()
    if (current && current.analysisId !== analysisId && current.documentId !== documentId) return false
    window.sessionStorage.removeItem(ANALYSIS_ID_KEY)
    window.sessionStorage.removeItem(ANALYSIS_REFERENCE_KEY)
    return true
  } catch {
    return false
  }
}

function isUsableAnalysis(analysis, expectedId, now) {
  if (
    !analysis ||
    normalizeAnalysisId(analysis.analysisId) !== expectedId ||
    analysis.status !== 'DONE' ||
    analysis.deletedAt != null ||
    analysis.deleted === true
  ) {
    return false
  }

  // Only a fresh successful document lookup may establish availability when
  // expiresAt is null. Stored references never set availabilityVerified.
  if (analysis.expiresAt == null) return analysis.availabilityVerified === true
  const expiresAt = typeof analysis.expiresAt === 'string'
    ? Date.parse(analysis.expiresAt)
    : NaN
  return Number.isFinite(expiresAt) && expiresAt > now
}

/**
 * Backend integration boundary. The factory remains injectable for tests;
 * AnalysisSessionProvider supplies the real Spring adapter in the app.
 *
 * backend.getAnalysis(id): GET /api/analyses/{analysisId}
 *   -> { analysisId, status, expiresAt: ISO-8601, deletedAt: null | ISO-8601 }
 * backend.getResult(id): GET /api/analyses/{analysisId}/result
 *   -> non-empty result object, or null for a missing/deleted result.
 *
 * The adapter must map the actual specification's fields to this frontend
 * contract, return null for 404/410, and throw for network/server errors.
 * Never call the Python/FastAPI server from the frontend.
 */
export function createAnalysisService({
  backend = null,
  getCurrentAnalysisId = readCurrentAnalysisId,
  now = Date.now,
} = {}) {
  return {
    async getCompletedAnalysis() {
      if (!backend) return null
      const id = normalizeAnalysisId(await getCurrentAnalysisId())
      if (!id) return null

      const analysis = await backend.getAnalysis(id)
      if (!isUsableAnalysis(analysis, id, now())) return null

      const result = await backend.getResult(id)
      if (
        !result ||
        typeof result !== 'object' ||
        Array.isArray(result) ||
        Object.keys(result).length === 0 ||
        !isUsableAnalysis(analysis, id, now())
      ) {
        return null
      }
      return { analysis, result }
    },
  }
}

export const analysisService = createAnalysisService()
