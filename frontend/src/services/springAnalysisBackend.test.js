import { describe, expect, it, vi } from 'vitest'
import { createSpringAnalysisBackend } from './springAnalysisBackend.js'
import { createAnalysisService, readAnalysisReference, rememberAnalysisId } from './analysisService.js'
import { ApiError } from '../api/springApi.js'
import { readyDocument, completedResult } from '../test/analysisFixtures.js'

function createLookup({ document = readyDocument, status = 'DONE', result = completedResult } = {}) {
  const api = {
    getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status, ...(status === 'RUNNING' ? { step: 'EXTRACTING', stepIndex: 1, stepCount: 4 } : {}), ...(status === 'FAILED' ? { error: { code: 'ANALYSIS_FAILED', message: '분석 실패' } } : {}) }),
    getDocument: vi.fn().mockResolvedValue(document),
    getResult: vi.fn().mockResolvedValue(result),
  }
  const reference = { analysisId: completedResult.analysisId, documentId: readyDocument.documentId, expiresAt: readyDocument.expiresAt }
  const service = createAnalysisService({
    backend: createSpringAnalysisBackend(api, () => reference),
    getCurrentAnalysisId: () => reference.analysisId,
  })
  return { api, service, reference }
}

describe('real Spring completion and retention adapter', () => {
  it('obtains expiry from the actual document endpoint instead of the analysis status', async () => {
    const { api, service } = createLookup()
    const completed = await service.getCompletedAnalysis()
    expect(completed.analysis.expiresAt).toBe(readyDocument.expiresAt)
    expect(completed.analysis.documentId).toBe(readyDocument.documentId)
    expect(api.getDocument).toHaveBeenCalledWith(readyDocument.documentId)
    expect(completed.result).toBe(completedResult)
  })
  it('does not trust a stored future deadline when the server returns an expired document', async () => {
    const { api, service } = createLookup({ document: { ...readyDocument, expiresAt: '2000-01-01T00:00:00Z' } })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(api.getResult).not.toHaveBeenCalled()
  })
  it.each(['QUEUED', 'RUNNING', 'FAILED'])('does not request results for %s', async (status) => {
    const { api, service } = createLookup({ status })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(api.getDocument).not.toHaveBeenCalled()
    expect(api.getResult).not.toHaveBeenCalled()
  })
  it.each([404, 410])('treats server deletion/expiry HTTP %s as no usable result', async (status) => {
    const { api, service } = createLookup()
    api.getDocument.mockRejectedValue(new ApiError('NOT_FOUND', 'gone', { status }))
    expect(await service.getCompletedAnalysis()).toBeNull()
  })
  it('does not invent a deadline if the server returns null but does revalidate server availability', async () => {
    const { api, service } = createLookup({ document: { ...readyDocument, expiresAt: null } })
    const completed = await service.getCompletedAnalysis()
    expect(completed.analysis.expiresAt).toBeNull()
    expect(completed.analysis.availabilityVerified).toBe(true)
    api.getDocument.mockRejectedValue(new ApiError('NOT_FOUND', 'expired', { status: 404 }))
    expect(await service.getCompletedAnalysis()).toBeNull()
  })
  it('refuses mismatched or empty result schemas', async () => {
    const { service } = createLookup({ result: { analysisId: 'other-analysis' } })
    await expect(service.getCompletedAnalysis()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('stores only opaque references and expiry, never document content or result payload', () => {
    rememberAnalysisId(completedResult.analysisId, { ...readyDocument, result: completedResult })
    expect(readAnalysisReference()).toEqual({ analysisId: completedResult.analysisId, documentId: readyDocument.documentId, expiresAt: readyDocument.expiresAt })
    const stored = window.sessionStorage.getItem('deulda.analysisReference')
    expect(stored).not.toContain(readyDocument.productName)
    expect(stored).not.toContain('groups')
    expect(window.localStorage.length).toBe(0)
  })
})
